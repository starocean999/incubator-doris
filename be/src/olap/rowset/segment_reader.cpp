// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "olap/rowset/segment_reader.h"

#include <sys/mman.h>

#include <istream>

#include "olap/file_stream.h"
#include "olap/in_stream.h"
#include "olap/olap_cond.h"
#include "olap/out_stream.h"
#include "olap/row_block.h"
#include "olap/rowset/segment_group.h"

namespace doris {

static const uint32_t MIN_FILTER_BLOCK_NUM = 10;

SegmentReader::SegmentReader(const std::string file, SegmentGroup* segment_group,
                             uint32_t segment_id, const std::vector<uint32_t>& used_columns,
                             const std::set<uint32_t>& load_bf_columns,
                             const Conditions* conditions, const DeleteHandler* delete_handler,
                             const DelCondSatisfied delete_status, Cache* lru_cache,
                             RuntimeState* runtime_state, OlapReaderStatistics* stats,
                             const std::shared_ptr<MemTracker>& parent_tracker)
        : _file_name(file),
          _segment_group(segment_group),
          _segment_id(segment_id),
          _used_columns(used_columns),
          _load_bf_columns(load_bf_columns),
          _conditions(conditions),
          _delete_handler(delete_handler),
          _delete_status(delete_status),
          _eof(false),
          _end_block(-1),
          // Make sure that the first call to _move_to_next_row will execute seek_to_block
          _block_count(0),
          _num_rows_in_block(0),
          _null_supported(false),
          _mmap_buffer(nullptr),
          _include_blocks(nullptr),
          _is_using_mmap(false),
          _is_data_loaded(false),
          _buffer_size(0),
          _tracker(MemTracker::create_tracker(-1, "SegmentReader:" + file, parent_tracker)),
          _mem_pool(new MemPool(_tracker.get())),
          _shared_buffer(nullptr),
          _lru_cache(lru_cache),
          _runtime_state(runtime_state),
          _stats(stats) {}

SegmentReader::~SegmentReader() {
    SAFE_DELETE(_shared_buffer);
    SAFE_DELETE_ARRAY(_include_blocks);

    for (auto& index_it : _indices) {
        SAFE_DELETE(index_it.second);
    }

    for (auto& bf_it : _bloom_filters) {
        SAFE_DELETE(bf_it.second);
    }

    for (auto handle : _cache_handle) {
        if (handle != nullptr) {
            _lru_cache->release(handle);
        }
    }

    _lru_cache = nullptr;
    _file_handler.close();

    for (auto& it : _streams) {
        delete it.second;
    }

    for (auto reader : _column_readers) {
        delete reader;
    }

    if (_is_using_mmap) {
        SAFE_DELETE(_mmap_buffer);
    }
}

OLAPStatus SegmentReader::_check_file_version() {
    if (_header_message().magic_string().compare("COLUMN DATA") != 0) {
        OLAP_LOG_WARNING("not valid column data file, [magic_string = %s]",
                         _header_message().magic_string().c_str());
        return OLAP_ERR_FILE_FORMAT_ERROR;
    }

    if (_header_message().version() > CURRENT_COLUMN_DATA_VERSION) {
        OLAP_LOG_WARNING(
                "this file may generated by olap/ngine of higher version. "
                "reading it would cause some unexpected error, [found version = %d]",
                _header_message().version());
    }

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_load_segment_file() {
    OLAPStatus res = OLAP_SUCCESS;

    res = _file_handler.open_with_cache(_file_name, O_RDONLY);
    if (OLAP_SUCCESS != res) {
        LOG(WARNING) << "fail to open segment file. [file='" << _file_name << "']";
        return res;
    }

    //VLOG_NOTICE << "seg file : " << _file_name;
    // In file_header.unserialize(), it validates file length, signature, checksum of protobuf.
    _file_header = _segment_group->get_seg_pb(_segment_id);
    _null_supported = _segment_group->get_null_supported(_segment_id);
    _header_length = _file_header->size();

    res = _check_file_version();
    if (OLAP_SUCCESS != res) {
        OLAP_LOG_WARNING("file header corrupted or generated by higher version olap/ngine.");
        return res;
    }

    // If mmap is needed, then map
    if (_is_using_mmap) {
        _mmap_buffer = StorageByteBuffer::mmap(&_file_handler, 0, PROT_READ, MAP_PRIVATE);

        if (nullptr == _mmap_buffer) {
            OLAP_LOG_WARNING("fail to call mmap, using default mode");
            return OLAP_ERR_MALLOC_ERROR;
        }
    }

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_set_decompressor() {
    switch (_header_message().compress_kind()) {
    case COMPRESS_NONE: {
        _decompressor = nullptr;
        break;
    }
#ifdef DORIS_WITH_LZO
    case COMPRESS_LZO: {
        _decompressor = lzo_decompress;
        break;
    }
#endif
    case COMPRESS_LZ4: {
        _decompressor = lz4_decompress;
        break;
    }
    default: {
        OLAP_LOG_WARNING("unknown decompressor");
        return OLAP_ERR_PARSE_PROTOBUF_ERROR;
    }
    }
    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_set_segment_info() {
    _num_rows_in_block = _header_message().num_rows_per_block();
    if (_num_rows_in_block == 0) {
        _num_rows_in_block = _segment_group->get_num_rows_per_row_block();
    }

    _set_column_map();
    OLAPStatus res = _set_decompressor();
    if (OLAP_SUCCESS != res) {
        OLAP_LOG_WARNING("fail to get decompressor.");
        return res;
    }
    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::init(bool is_using_cache) {
    SCOPED_RAW_TIMER(&_stats->index_load_ns);

    OLAPStatus res = OLAP_SUCCESS;
    res = _load_segment_file();
    if (OLAP_SUCCESS != res) {
        OLAP_LOG_WARNING("fail to load segment file. ");
        return res;
    }
    // File header
    res = _set_segment_info();
    if (OLAP_SUCCESS != res) {
        OLAP_LOG_WARNING("fail to set segment info. ");
        return res;
    }

    _shared_buffer =
            StorageByteBuffer::create(_header_message().stream_buffer_size() + sizeof(StreamHead));
    if (_shared_buffer == nullptr) {
        OLAP_LOG_WARNING("fail to create shared buffer. [size=%lu]", sizeof(StorageByteBuffer));
        return OLAP_ERR_MALLOC_ERROR;
    }

    res = _pick_columns();
    if (OLAP_SUCCESS != res) {
        OLAP_LOG_WARNING("fail to pick columns");
        return res;
    }

    res = _load_index(is_using_cache);
    if (OLAP_SUCCESS != res) {
        OLAP_LOG_WARNING("fail to load index stream");
        return res;
    }

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::seek_to_block(uint32_t first_block, uint32_t last_block,
                                        bool without_filter, uint32_t* next_block_id, bool* eof) {
    OLAPStatus res = OLAP_SUCCESS;

    if (!_is_data_loaded) {
        _reset_readers();
        res = _read_all_data_streams(&_buffer_size);
        if (res != OLAP_SUCCESS) {
            OLAP_LOG_WARNING("fail to read data stream");
            return res;
        }

        OLAPStatus res = _create_reader(&_buffer_size);
        if (res != OLAP_SUCCESS) {
            OLAP_LOG_WARNING("fail to create reader");
            return res;
        }

        _is_data_loaded = true;
    }

    // If seek to block position, all stat will reset to initial
    _eof = false;
    _end_block = last_block >= _block_count ? _block_count - 1 : last_block;
    _without_filter = without_filter;
    delete[] _include_blocks;
    _include_blocks = nullptr;
    if (!_without_filter) {
        /*
         * row batch may be not empty before next read,
         * should be clear here, otherwise dirty records
         * will be read.
         */
        _remain_block = last_block - first_block + 1;
        res = _pick_row_groups(first_block, last_block);
        if (OLAP_SUCCESS != res) {
            OLAP_LOG_WARNING("fail to pick row groups");
            return res;
        }
    }
    _seek_to_block(first_block, without_filter);
    *next_block_id = _next_block_id;
    *eof = _eof;

    // Must seek block when starts a ScanKey.
    // In Doris, one block has 1024 rows.
    // 1. If the previous ScanKey scan rows multiple blocks,
    //    and also the final block has 1024 rows just right.
    // 2. The current ScanKey scan rows with number less than one block.
    // Under the two conditions, if not seek block, the position
    // of prefix shortkey columns is wrong.
    _need_to_seek_block = true;

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::get_block(VectorizedRowBatch* batch, uint32_t* next_block_id, bool* eof) {
    if (_eof) {
        *eof = true;
        return OLAP_SUCCESS;
    }

    // lazy seek
    _seek_to_block_directly(_next_block_id, batch->columns());

    int64_t num_rows_load = batch->limit();
    if (OLAP_UNLIKELY(_current_block_id == _block_count - 1)) {
        int64_t num_rows_left =
                _header_message().number_of_rows() - _num_rows_in_block * _current_block_id;
        num_rows_load = std::min(num_rows_load, num_rows_left);
    }

    auto res = _load_to_vectorized_row_batch(batch, num_rows_load);
    if (res != OLAP_SUCCESS) {
        LOG(WARNING) << "fail to load block to vectorized_row_batch. res:" << res;
        return res;
    }

    _seek_to_block(_next_block_id + 1, _without_filter);

    *next_block_id = _next_block_id;
    *eof = _eof;
    return OLAP_SUCCESS;
}

void SegmentReader::_set_column_map() {
    _encodings_map.clear();
    _tablet_id_to_unique_id_map.clear();
    _unique_id_to_tablet_id_map.clear();
    _unique_id_to_segment_id_map.clear();

    for (ColumnId table_column_id : _used_columns) {
        ColumnId unique_column_id = tablet_schema().column(table_column_id).unique_id();
        _tablet_id_to_unique_id_map[table_column_id] = unique_column_id;
        _unique_id_to_tablet_id_map[unique_column_id] = table_column_id;
    }

    for (ColumnId table_column_id : _load_bf_columns) {
        ColumnId unique_column_id = tablet_schema().column(table_column_id).unique_id();
        _tablet_id_to_unique_id_map[table_column_id] = unique_column_id;
        _unique_id_to_tablet_id_map[unique_column_id] = table_column_id;
    }

    size_t segment_column_size = _header_message().column_size();
    for (ColumnId segment_column_id = 0; segment_column_id < segment_column_size;
         ++segment_column_id) {
        // If you can find it, create a mapping table
        ColumnId unique_column_id = _header_message().column(segment_column_id).unique_id();
        if (_unique_id_to_tablet_id_map.find(unique_column_id) !=
            _unique_id_to_tablet_id_map.end()) {
            _unique_id_to_segment_id_map[unique_column_id] = segment_column_id;
            // The encoding should be in the same order as the segment schema.
            _encodings_map[unique_column_id] = _header_message().column_encoding(segment_column_id);
        }
    }
}

OLAPStatus SegmentReader::_pick_columns() {
    for (uint32_t i : _used_columns) {
        ColumnId unique_column_id = _tablet_id_to_unique_id_map[i];
        _include_columns.insert(unique_column_id);
    }

    for (uint32_t i : _load_bf_columns) {
        ColumnId unique_column_id = _tablet_id_to_unique_id_map[i];
        _include_bf_columns.insert(unique_column_id);
    }

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_pick_delete_row_groups(uint32_t first_block, uint32_t last_block) {
    VLOG_TRACE << "pick for " << first_block << " to " << last_block << " for delete_condition";

    if (_delete_handler->empty()) {
        return OLAP_SUCCESS;
    }

    if (DEL_NOT_SATISFIED == _delete_status) {
        VLOG_TRACE << "the segment not satisfy the delete_conditions";
        return OLAP_SUCCESS;
    }

    for (auto& delete_condition : _delete_handler->get_delete_conditions()) {
        if (delete_condition.filter_version <= _segment_group->version().first) {
            continue;
        }

        for (int64_t j = first_block; j <= last_block; ++j) {
            if (DEL_SATISFIED == _include_blocks[j]) {
                //if state is DEL_SATISFIED, continue
                continue;
            }

            bool del_partial_satisfied = false;
            bool del_not_satisfied = false;
            for (auto& i : delete_condition.del_cond->columns()) {
                ColumnId table_column_id = i.first;
                ColumnId unique_column_id = _tablet_id_to_unique_id_map[table_column_id];
                if (0 == _unique_id_to_segment_id_map.count(unique_column_id)) {
                    continue;
                }
                StreamIndexReader* index_reader = _indices[unique_column_id];
                int del_ret = i.second->del_eval(index_reader->entry(j).column_statistic().pair());
                if (DEL_SATISFIED == del_ret) {
                    continue;
                } else if (DEL_PARTIAL_SATISFIED == del_ret) {
                    del_partial_satisfied = true;
                } else {
                    del_not_satisfied = true;
                    break;
                }
            }

            if (true == del_not_satisfied || 0 == delete_condition.del_cond->columns().size()) {
                //if state is DEL_PARTIAL_SATISFIED last_time, cannot be set as DEL_NOT_SATISFIED
                //it is special for for delete condition
                if (DEL_PARTIAL_SATISFIED == _include_blocks[j]) {
                    continue;
                } else {
                    _include_blocks[j] = DEL_NOT_SATISFIED;
                }
            } else if (true == del_partial_satisfied) {
                _include_blocks[j] = DEL_PARTIAL_SATISFIED;
                VLOG_TRACE << "filter block partially: " << j;
            } else {
                _include_blocks[j] = DEL_SATISFIED;
                --_remain_block;
                VLOG_TRACE << "filter block: " << j;
                if (j < _block_count - 1) {
                    _stats->rows_del_filtered += _num_rows_in_block;
                } else {
                    _stats->rows_del_filtered +=
                            _header_message().number_of_rows() - j * _num_rows_in_block;
                }
            }
        }
    }

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_init_include_blocks(uint32_t first_block, uint32_t last_block) {
    if (nullptr == _include_blocks) {
        _include_blocks = new (std::nothrow) uint8_t[_block_count];
        if (nullptr == _include_blocks) {
            OLAP_LOG_WARNING("fail to malloc include block array");
            return OLAP_ERR_MALLOC_ERROR;
        }
    }

    memset(_include_blocks, 0, _block_count);
    memset(_include_blocks + first_block, 1, _remain_block);

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_pick_row_groups(uint32_t first_block, uint32_t last_block) {
    VLOG_TRACE << "pick from " << first_block << " to " << last_block;

    if (first_block > last_block) {
        OLAP_LOG_WARNING("invalid block offset. [first_block=%u last_block=%u]", first_block,
                         last_block);
        return OLAP_ERR_INPUT_PARAMETER_ERROR;
    }

    OLAPStatus res = _init_include_blocks(first_block, last_block);
    if (OLAP_SUCCESS != res) {
        return res;
    }

    _pick_delete_row_groups(first_block, last_block);

    if (nullptr == _conditions || _conditions->columns().size() == 0) {
        return OLAP_SUCCESS;
    }

    OlapStopWatch timer;
    timer.reset();

    for (auto& i : _conditions->columns()) {
        FieldAggregationMethod aggregation = _get_aggregation_by_index(i.first);
        bool is_continue = (aggregation == OLAP_FIELD_AGGREGATION_NONE);
        if (!is_continue) {
            continue;
        }

        ColumnId table_column_id = i.first;
        ColumnId unique_column_id = _tablet_id_to_unique_id_map[table_column_id];
        if (0 == _unique_id_to_segment_id_map.count(unique_column_id)) {
            continue;
        }
        StreamIndexReader* index_reader = _indices[unique_column_id];
        for (int64_t j = first_block; j <= last_block; ++j) {
            if (_include_blocks[j] == DEL_SATISFIED) {
                continue;
            }

            if (!i.second->eval(index_reader->entry(j).column_statistic().pair())) {
                _include_blocks[j] = DEL_SATISFIED;
                --_remain_block;

                if (j < _block_count - 1) {
                    _stats->rows_stats_filtered += _num_rows_in_block;
                } else {
                    _stats->rows_stats_filtered +=
                            _header_message().number_of_rows() - j * _num_rows_in_block;
                }
            }
        }
    }

    if (_remain_block < MIN_FILTER_BLOCK_NUM) {
        VLOG_TRACE << "bloom filter is ignored for too few block remained. "
                   << "remain_block=" << _remain_block
                   << ", const_time=" << timer.get_elapse_time_us();
        return OLAP_SUCCESS;
    }

    for (uint32_t i : _load_bf_columns) {
        FieldAggregationMethod aggregation = _get_aggregation_by_index(i);
        bool is_continue = (aggregation == OLAP_FIELD_AGGREGATION_NONE);
        if (!is_continue) {
            continue;
        }

        ColumnId table_column_id = i;
        ColumnId unique_column_id = _tablet_id_to_unique_id_map[table_column_id];
        if (0 == _unique_id_to_segment_id_map.count(unique_column_id)) {
            continue;
        }
        BloomFilterIndexReader* bf_reader = _bloom_filters[unique_column_id];
        for (int64_t j = first_block; j <= last_block; ++j) {
            if (_include_blocks[j] == DEL_SATISFIED) {
                continue;
            }

            if (!_conditions->columns().at(i)->eval(bf_reader->entry(j))) {
                _include_blocks[j] = DEL_SATISFIED;
                --_remain_block;
                if (j < _block_count - 1) {
                    _stats->rows_stats_filtered += _num_rows_in_block;
                } else {
                    _stats->rows_stats_filtered +=
                            _header_message().number_of_rows() - j * _num_rows_in_block;
                }
            }
        }
    }

    VLOG_TRACE << "pick row groups finished. remain_block=" << _remain_block
               << ", const_time=" << timer.get_elapse_time_us();
    return OLAP_SUCCESS;
}

CacheKey SegmentReader::_construct_index_stream_key(char* buf, size_t len,
                                                    const std::string& file_name,
                                                    ColumnId unique_column_id,
                                                    StreamInfoMessage::Kind kind) {
    char* current = buf;
    size_t remain_len = len;
    OLAP_CACHE_STRING_TO_BUF(current, file_name, remain_len);
    OLAP_CACHE_NUMERIC_TO_BUF(current, unique_column_id, remain_len);
    OLAP_CACHE_NUMERIC_TO_BUF(current, kind, remain_len);

    return CacheKey(buf, len - remain_len);
}

void SegmentReader::_delete_cached_index_stream(const CacheKey& key, void* value) {
    char* buffer = reinterpret_cast<char*>(value);
    SAFE_DELETE_ARRAY(buffer);
}

OLAPStatus SegmentReader::_load_index(bool is_using_cache) {
    OLAPStatus res = OLAP_SUCCESS;

    int32_t handle_num = _get_included_row_index_stream_num();
    _cache_handle.resize(handle_num, nullptr);

    ReadOnlyFileStream stream(&_file_handler, &_shared_buffer, _decompressor,
                              _header_message().stream_buffer_size(), _stats);
    res = stream.init();
    if (OLAP_SUCCESS != res) {
        OLAP_LOG_WARNING("fail to init stream. [res=%d]", res);
        return res;
    }

    _indices.clear();
    _bloom_filters.clear();
    uint64_t stream_length = 0;
    int32_t cache_handle_index = 0;
    uint64_t stream_offset = _header_length;
    int64_t expected_blocks =
            static_cast<int64_t>(ceil(static_cast<double>(_header_message().number_of_rows()) /
                                      _header_message().num_rows_per_block()));
    for (int64_t stream_index = 0; stream_index < _header_message().stream_info_size();
         ++stream_index, stream_offset += stream_length) {
        // Find the required index, although some indexes do not need to be read
        // Take, but in order to get the offset, it is still necessary to calculate it again
        // Otherwise, the correct streamoffset cannot be obtained
        const StreamInfoMessage& message = _header_message().stream_info(stream_index);
        stream_length = message.length();
        ColumnId unique_column_id = message.column_unique_id();
        if (0 == _unique_id_to_segment_id_map.count(unique_column_id)) {
            continue;
        }

        if ((_is_column_included(unique_column_id) &&
             message.kind() == StreamInfoMessage::ROW_INDEX) ||
            (_is_bf_column_included(unique_column_id) &&
             message.kind() == StreamInfoMessage::BLOOM_FILTER)) {
        } else {
            continue;
        }

        ColumnId table_column_id = _unique_id_to_tablet_id_map[unique_column_id];
        FieldType type = _get_field_type_by_index(table_column_id);

        char* stream_buffer = nullptr;
        char key_buf[OLAP_LRU_CACHE_MAX_KEY_LENGTH];
        CacheKey key =
                _construct_index_stream_key(key_buf, sizeof(key_buf), _file_handler.file_name(),
                                            unique_column_id, message.kind());
        _cache_handle[cache_handle_index] = _lru_cache->lookup(key);

        if (nullptr != _cache_handle[cache_handle_index]) {
            // 1. If you are in lru, take out the buffer and use it to initialize the index reader
            is_using_cache = true;
            stream_buffer =
                    reinterpret_cast<char*>(_lru_cache->value(_cache_handle[cache_handle_index]));
        } else {
            // 2. If it is not in lru, you need to create an index stream.
            stream_buffer = new (std::nothrow) char[stream_length];
            if (nullptr == stream_buffer) {
                OLAP_LOG_WARNING(
                        "fail to malloc index stream. "
                        "[column_unique_id = %u, offset = %lu]",
                        unique_column_id, stream_offset);
                return OLAP_ERR_MALLOC_ERROR;
            }

            size_t read_length = stream_length;
            stream.reset(stream_offset, stream_length);
            res = stream.read_all(stream_buffer, &read_length);
            if (OLAP_SUCCESS != res) {
                OLAP_LOG_WARNING("read index fail");
                return OLAP_ERR_FILE_FORMAT_ERROR;
            }

            if (is_using_cache) {
                // Put the read index into lru.
                _cache_handle[cache_handle_index] = _lru_cache->insert(
                        key, stream_buffer, stream_length, &_delete_cached_index_stream);
                if (nullptr == _cache_handle[cache_handle_index]) {
                    // It may be that malloc in cache insert failed, first return success
                    LOG(FATAL) << "fail to insert lru cache.";
                }
            }
        }
        cache_handle_index++;

        if (message.kind() == StreamInfoMessage::ROW_INDEX) {
            StreamIndexReader* index_message = new (std::nothrow) StreamIndexReader;
            if (index_message == nullptr) {
                OLAP_LOG_WARNING("fail to malloc memory. [size=%lu]", sizeof(StreamIndexReader));
                return OLAP_ERR_MALLOC_ERROR;
            }

            res = index_message->init(stream_buffer, stream_length, type, is_using_cache,
                                      _null_supported);
            if (OLAP_SUCCESS != res) {
                OLAP_LOG_WARNING("init index from cache fail");
                return res;
            }

            _indices[unique_column_id] = index_message;

            // The number of entries for each index should be the same, that is, the number of blocks
            _block_count = index_message->entry_count();
        } else {
            BloomFilterIndexReader* bf_message = new (std::nothrow) BloomFilterIndexReader;
            if (bf_message == nullptr) {
                OLAP_LOG_WARNING("fail to malloc memory. [size=%lu]",
                                 sizeof(BloomFilterIndexReader));
                return OLAP_ERR_MALLOC_ERROR;
            }

            res = bf_message->init(stream_buffer, stream_length, is_using_cache,
                                   _header_message().bf_hash_function_num(),
                                   _header_message().bf_bit_num());
            if (res != OLAP_SUCCESS) {
                OLAP_LOG_WARNING("fail to init bloom filter reader. [res=%d]", res);
                return res;
            }

            _bloom_filters[unique_column_id] = bf_message;

            // The number of entries for each index should be the same, that is, the number of blocks
            _block_count = bf_message->entry_count();
        }

        if (_block_count != expected_blocks) {
            LOG(WARNING) << "something wrong while reading index, expected=" << expected_blocks
                         << ", actual=" << _block_count;
            LOG(WARNING) << "_header_message().number_of_rows()="
                         << _header_message().number_of_rows()
                         << ", _header_message().num_rows_per_block()="
                         << _header_message().num_rows_per_block()
                         << ", tablet_id=" << _segment_group->get_tablet_id() << ", version='"
                         << _segment_group->version().first << "-"
                         << _segment_group->version().second << "'";
            return OLAP_ERR_FILE_FORMAT_ERROR;
        }
    }

    VLOG_TRACE << "found index entry count: " << _block_count;
    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_read_all_data_streams(size_t* buffer_size) {
    int64_t stream_offset = _header_length;
    uint64_t stream_length = 0;

    // Each stream is one piece
    for (int64_t stream_index = 0; stream_index < _header_message().stream_info_size();
         ++stream_index, stream_offset += stream_length) {
        const StreamInfoMessage& message = _header_message().stream_info(stream_index);
        stream_length = message.length();
        ColumnId unique_column_id = message.column_unique_id();

        if (_unique_id_to_segment_id_map.count(unique_column_id) == 0) {
            continue;
        }

        if (_include_columns.find(unique_column_id) == _include_columns.end() &&
            _include_bf_columns.find(unique_column_id) == _include_bf_columns.end()) {
            continue;
        }

        if (message.kind() == StreamInfoMessage::ROW_INDEX ||
            message.kind() == StreamInfoMessage::BLOOM_FILTER) {
            continue;
        }

        StreamName name(unique_column_id, message.kind());
        std::unique_ptr<ReadOnlyFileStream> stream(new (std::nothrow) ReadOnlyFileStream(
                &_file_handler, &_shared_buffer, stream_offset, stream_length, _decompressor,
                _header_message().stream_buffer_size(), _stats));
        if (stream == nullptr) {
            OLAP_LOG_WARNING("fail to create stream");
            return OLAP_ERR_MALLOC_ERROR;
        }

        OLAPStatus res = stream->init();
        if (OLAP_SUCCESS != res) {
            OLAP_LOG_WARNING("fail to init stream");
            return res;
        }

        *buffer_size += stream->get_buffer_size();
        _streams[name] = stream.release();
    }

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_create_reader(size_t* buffer_size) {
    _column_readers.resize(_segment_group->get_tablet_schema().num_columns(), nullptr);
    _column_indices.resize(_segment_group->get_tablet_schema().num_columns(), nullptr);
    for (auto table_column_id : _used_columns) {
        ColumnId unique_column_id = _tablet_id_to_unique_id_map[table_column_id];
        // Currently, there will be no inconsistencies in the schema of the table and the segment.
        std::unique_ptr<ColumnReader> reader(ColumnReader::create(
                table_column_id, _segment_group->get_tablet_schema(), _unique_id_to_tablet_id_map,
                _unique_id_to_segment_id_map, _encodings_map));
        if (reader == nullptr) {
            OLAP_LOG_WARNING("fail to create reader");
            return OLAP_ERR_MALLOC_ERROR;
        }

        auto res = reader->init(&_streams, _num_rows_in_block, _mem_pool.get(), _stats);
        if (res != OLAP_SUCCESS) {
            OLAP_LOG_WARNING("fail to init reader");
            return res;
        }

        *buffer_size += reader->get_buffer_size();
        _column_readers[table_column_id] = reader.release();
        if (_indices.count(unique_column_id) != 0) {
            _column_indices[table_column_id] = _indices[unique_column_id];
        }
    }

    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_seek_to_block_directly(int64_t block_id,
                                                  const std::vector<uint32_t>& cids) {
    if (!_need_to_seek_block && block_id == _current_block_id) {
        // no need to execute seek
        return OLAP_SUCCESS;
    }
    SCOPED_RAW_TIMER(&_stats->block_seek_ns);
    for (auto cid : cids) {
        // If column is added through schema change, column index may not exist because of
        // linked schema change. So we need to ignore this column's seek
        if (_column_indices[cid] == nullptr) {
            continue;
        }

        OLAPStatus res = OLAP_SUCCESS;
        PositionProvider position(&_column_indices[cid]->entry(block_id));
        if (OLAP_SUCCESS != (res = _column_readers[cid]->seek(&position))) {
            if (OLAP_ERR_COLUMN_STREAM_EOF == res) {
                VLOG_TRACE << "Stream EOF. tablet_id=" << _segment_group->get_tablet_id()
                           << ", column_id=" << _column_readers[cid]->column_unique_id()
                           << ", block_id=" << block_id;
                return OLAP_ERR_DATA_EOF;
            } else {
                OLAP_LOG_WARNING(
                        "fail to seek to block. "
                        "[tablet_id=%ld column_id=%u block_id=%lu]",
                        _segment_group->get_tablet_id(), _column_readers[cid]->column_unique_id(),
                        block_id);
                return OLAP_ERR_COLUMN_SEEK_ERROR;
            }
        }
    }
    _current_block_id = block_id;
    _need_to_seek_block = false;
    return OLAP_SUCCESS;
}

OLAPStatus SegmentReader::_reset_readers() {
    VLOG_TRACE << _streams.size() << " stream in total.";

    for (std::map<StreamName, ReadOnlyFileStream*>::iterator it = _streams.begin();
         it != _streams.end(); ++it) {
        delete it->second;
    }

    _streams.clear();

    for (std::vector<ColumnReader*>::iterator it = _column_readers.begin();
         it != _column_readers.end(); ++it) {
        if ((*it) == nullptr) {
            continue;
        }
        delete (*it);
    }

    _column_readers.clear();
    _eof = false;
    return OLAP_SUCCESS;
}

void SegmentReader::_seek_to_block(int64_t block_id, bool without_filter) {
    if (_include_blocks != nullptr && !without_filter) {
        while (block_id <= _end_block && _include_blocks[block_id] == DEL_SATISFIED) {
            block_id++;
        }
    }
    if (block_id > _end_block) {
        _eof = true;
    }
    _next_block_id = block_id;
}

OLAPStatus SegmentReader::_load_to_vectorized_row_batch(VectorizedRowBatch* batch, size_t size) {
    SCOPED_RAW_TIMER(&_stats->block_load_ns);
    MemPool* mem_pool = batch->mem_pool();
    for (auto cid : batch->columns()) {
        auto reader = _column_readers[cid];
        auto res = reader->next_vector(batch->column(cid), size, mem_pool);
        if (res != OLAP_SUCCESS) {
            LOG(WARNING) << "fail to read next, res=" << res
                         << ", column=" << reader->column_unique_id() << ", size=" << size;
            return res;
        }
    }
    batch->set_size(size);
    if (_include_blocks != nullptr) {
        batch->set_block_status(_include_blocks[_current_block_id]);
    } else {
        batch->set_block_status(DEL_PARTIAL_SATISFIED);
    }
    // If size is just _num_rows_in_block, after read, we point to next block start,
    // so we increase _current_block_id
    if (size == _num_rows_in_block) {
        _current_block_id++;
    } else {
        _need_to_seek_block = true;
    }

    _stats->blocks_load++;
    _stats->raw_rows_read += size;

    return OLAP_SUCCESS;
}

} // namespace doris
