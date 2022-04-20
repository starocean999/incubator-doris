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
// This file is copied from
// https://github.com/ClickHouse/ClickHouse/blob/master/src/Common/ColumnsHashing.h
// and modified by Doris

#pragma once

#include <memory>

#include "vec/columns/column_string.h"
#include "vec/common/arena.h"
#include "vec/common/assert_cast.h"
#include "vec/common/columns_hashing_impl.h"
#include "vec/common/hash_table/hash_table.h"
#include "vec/common/hash_table/hash_table_key_holder.h"
#include "vec/common/unaligned.h"

namespace doris::vectorized {

namespace ColumnsHashing {

/// For the case when there is one numeric key.
/// UInt8/16/32/64 for any type with corresponding bit width.
template <typename Value, typename Mapped, typename FieldType, bool use_cache = true>
struct HashMethodOneNumber : public columns_hashing_impl::HashMethodBase<
                                     HashMethodOneNumber<Value, Mapped, FieldType, use_cache>,
                                     Value, Mapped, use_cache> {
    using Self = HashMethodOneNumber<Value, Mapped, FieldType, use_cache>;
    using Base = columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache>;

    const char* vec;

    /// If the keys of a fixed length then key_sizes contains their lengths, empty otherwise.
    HashMethodOneNumber(const ColumnRawPtrs& key_columns, const Sizes& /*key_sizes*/,
                        const HashMethodContextPtr&, const Sizes& offsets_ = {}) {
        vec = key_columns[0]->get_raw_data().data;
    }

    HashMethodOneNumber(const IColumn* column) { vec = column->get_raw_data().data; }

    /// Creates context. Method is called once and result context is used in all threads.
    using Base::createContext; /// (const HashMethodContext::Settings &) -> HashMethodContextPtr

    /// Emplace key into HashTable or HashMap. If Data is HashMap, returns ptr to value, otherwise nullptr.
    /// Data is a HashTable where to insert key from column's row.
    /// For Serialized method, key may be placed in pool.
    using Base::emplace_key; /// (Data & data, size_t row, Arena & pool) -> EmplaceResult

    /// Find key into HashTable or HashMap. If Data is HashMap and key was found, returns ptr to value, otherwise nullptr.
    using Base::find_key; /// (Data & data, size_t row, Arena & pool) -> FindResult

    /// Get hash value of row.
    using Base::get_hash; /// (const Data & data, size_t row, Arena & pool) -> size_t

    /// Is used for default implementation in HashMethodBase.
    FieldType get_key_holder(size_t row, Arena&) const {
        return unaligned_load<FieldType>(vec + row * sizeof(FieldType));
    }
};

/// For the case when there is one string key.
template <typename Value, typename Mapped, bool place_string_to_arena = true, bool use_cache = true>
struct HashMethodString : public columns_hashing_impl::HashMethodBase<
                                  HashMethodString<Value, Mapped, place_string_to_arena, use_cache>,
                                  Value, Mapped, use_cache> {
    using Self = HashMethodString<Value, Mapped, place_string_to_arena, use_cache>;
    using Base = columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache>;

    const IColumn::Offset* offsets;
    const UInt8* chars;

    HashMethodString(const ColumnRawPtrs& key_columns, const Sizes& /*key_sizes*/,
                     const HashMethodContextPtr&, const Sizes& offsets_ = {}) {
        const IColumn& column = *key_columns[0];
        const ColumnString& column_string = assert_cast<const ColumnString&>(column);
        offsets = column_string.get_offsets().data();
        chars = column_string.get_chars().data();
    }

    auto get_key_holder(ssize_t row, [[maybe_unused]] Arena& pool) const {
        StringRef key(chars + offsets[row - 1], offsets[row] - offsets[row - 1] - 1);

        if constexpr (place_string_to_arena) {
            return ArenaKeyHolder {key, pool};
        } else {
            return key;
        }
    }

protected:
    friend class columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache>;
};

/// For the case when there is one fixed-length string key.
template <typename Value, typename Mapped, bool place_string_to_arena = true, bool use_cache = true>
struct HashMethodFixedString
        : public columns_hashing_impl::HashMethodBase<
                  HashMethodFixedString<Value, Mapped, place_string_to_arena, use_cache>, Value,
                  Mapped, use_cache> {
    using Self = HashMethodFixedString<Value, Mapped, place_string_to_arena, use_cache>;
    using Base = columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache>;

    size_t n;
    const UInt8* chars;

    HashMethodFixedString(const ColumnRawPtrs& key_columns, const Sizes& /*key_sizes*/,
                          const HashMethodContextPtr&, const Sizes& offsets_ = {}) {
        const IColumn& column = *key_columns[0];
        const ColumnString& column_string = assert_cast<const ColumnString&>(column);
        n = column_string.get_offsets().data()[0];
        chars = column_string.get_chars().data();
    }

    auto get_key_holder(size_t row, [[maybe_unused]] Arena& pool) const {
        StringRef key(chars + row * n, n);

        if constexpr (place_string_to_arena) {
            return ArenaKeyHolder {key, pool};
        } else {
            return key;
        }
    }

protected:
    friend class columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache>;
};

template <typename Value, typename Mapped, typename FieldType, bool use_cache = true>
struct HashMethodShortString : public columns_hashing_impl::HashMethodBase<
                                     HashMethodShortString<Value, Mapped, FieldType, use_cache>,
                                     Value, Mapped, use_cache> {
    using Self = HashMethodShortString<Value, Mapped, FieldType, use_cache>;
    using Base = columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache>;

    //size_t n;
    const IColumn::Offset* offsets;
    const UInt8* chars;

    /// If the keys of a fixed length then key_sizes contains their lengths, empty otherwise.
    HashMethodShortString(const ColumnRawPtrs& key_columns, const Sizes& /*key_sizes*/,
                        const HashMethodContextPtr&, const Sizes& offsets_ = {}) {
        const IColumn& column = *key_columns[0];
        const ColumnString& column_string = assert_cast<const ColumnString&>(column);
        //n = column_string.get_offsets().data()[0];
        offsets = column_string.get_offsets().data();
        chars = column_string.get_chars().data();
    }

    /// Creates context. Method is called once and result context is used in all threads.
    using Base::createContext; /// (const HashMethodContext::Settings &) -> HashMethodContextPtr

    /// Emplace key into HashTable or HashMap. If Data is HashMap, returns ptr to value, otherwise nullptr.
    /// Data is a HashTable where to insert key from column's row.
    /// For Serialized method, key may be placed in pool.
    using Base::emplace_key; /// (Data & data, size_t row, Arena & pool) -> EmplaceResult

    /// Find key into HashTable or HashMap. If Data is HashMap and key was found, returns ptr to value, otherwise nullptr.
    using Base::find_key; /// (Data & data, size_t row, Arena & pool) -> FindResult

    /// Get hash value of row.
    using Base::get_hash; /// (const Data & data, size_t row, Arena & pool) -> size_t

    /// Is used for default implementation in HashMethodBase.
    FieldType get_key_holder(size_t row, Arena&) const {
        FieldType res {};
        //memcpy(&res, chars + row * n, n - 1);
        memcpy(&res, chars + offsets[row - 1], offsets[row] - offsets[row - 1] - 1);
        return res;
    }
};

/** Hash by concatenating serialized key values.
  * The serialized value differs in that it uniquely allows to deserialize it, having only the position with which it starts.
  * That is, for example, for strings, it contains first the serialized length of the string, and then the bytes.
  * Therefore, when aggregating by several strings, there is no ambiguity.
  */
template <typename Value, typename Mapped>
struct HashMethodSerialized
        : public columns_hashing_impl::HashMethodBase<HashMethodSerialized<Value, Mapped>, Value,
                                                      Mapped, false> {
    using Self = HashMethodSerialized<Value, Mapped>;
    using Base = columns_hashing_impl::HashMethodBase<Self, Value, Mapped, false>;

    ColumnRawPtrs key_columns;
    size_t keys_size;

    HashMethodSerialized(const ColumnRawPtrs& key_columns_, const Sizes& /*key_sizes*/,
                         const HashMethodContextPtr&, const Sizes& offsets_ = {})
            : key_columns(key_columns_), keys_size(key_columns_.size()) {}

protected:
    friend class columns_hashing_impl::HashMethodBase<Self, Value, Mapped, false>;

    ALWAYS_INLINE SerializedKeyHolder get_key_holder(size_t row, Arena& pool) const {
        return SerializedKeyHolder {
                serialize_keys_to_pool_contiguous(row, keys_size, key_columns, pool), pool};
    }
};

/// For the case when there is one string key.
template <typename Value, typename Mapped, bool use_cache = true>
struct HashMethodHashed
        : public columns_hashing_impl::HashMethodBase<HashMethodHashed<Value, Mapped, use_cache>,
                                                      Value, Mapped, use_cache> {
    using Key = UInt128;
    using Self = HashMethodHashed<Value, Mapped, use_cache>;
    using Base = columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache>;

    ColumnRawPtrs key_columns;

    HashMethodHashed(ColumnRawPtrs key_columns_, const Sizes&, const HashMethodContextPtr&, const Sizes& offsets_ = {})
            : key_columns(std::move(key_columns_)) {}

    ALWAYS_INLINE Key get_key_holder(size_t row, Arena&) const {
        return hash128(row, key_columns.size(), key_columns);
    }
};

/// For the case when all keys are of fixed length, and they fit in N (for example, 128) bits.
template <typename Value, typename Key, typename Mapped, bool has_nullable_keys_ = false,
          bool use_cache = true>
struct HashMethodKeysFixed
        : private columns_hashing_impl::BaseStateKeysFixed<Key, has_nullable_keys_>,
          public columns_hashing_impl::HashMethodBase<
                  HashMethodKeysFixed<Value, Key, Mapped, has_nullable_keys_, use_cache>, Value,
                  Mapped, use_cache> {
    using Self = HashMethodKeysFixed<Value, Key, Mapped, has_nullable_keys_, use_cache>;
    using BaseHashed = columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache>;
    using Base = columns_hashing_impl::BaseStateKeysFixed<Key, has_nullable_keys_>;

    const Sizes& key_sizes;
    size_t keys_size;

    HashMethodKeysFixed(const ColumnRawPtrs& key_columns, const Sizes& key_sizes_,
                        const HashMethodContextPtr&)
            : Base(key_columns), key_sizes(key_sizes_), keys_size(key_columns.size()) {}

    ALWAYS_INLINE Key get_key_holder(size_t row, Arena&) const {
        if constexpr (has_nullable_keys_) {
            auto bitmap = Base::create_bitmap(row);
            return pack_fixed<Key>(row, keys_size, Base::get_actual_columns(), key_sizes, bitmap);
        } else {
            return pack_fixed<Key>(row, keys_size, Base::get_actual_columns(), key_sizes);
        }
    }
};

template <typename Value, typename Key, typename Mapped, bool has_nullable_keys_ = false,
          bool use_cache = true>
struct HashMethodKeysFixedForAgg
        : private columns_hashing_impl::BaseStateKeysFixed<Key, has_nullable_keys_>,
          public columns_hashing_impl::HashMethodBase<
                  HashMethodKeysFixedForAgg<Value, Key, Mapped, has_nullable_keys_, use_cache>, Value,
                  Mapped, use_cache, true> {
    using Self = HashMethodKeysFixedForAgg<Value, Key, Mapped, has_nullable_keys_, use_cache>;
    using BaseHashed = columns_hashing_impl::HashMethodBase<Self, Value, Mapped, use_cache, true>;
    using Base = columns_hashing_impl::BaseStateKeysFixed<Key, has_nullable_keys_>;

    const Sizes& key_sizes;
    size_t keys_size;
    Sizes offsets;

    HashMethodKeysFixedForAgg(const ColumnRawPtrs& key_columns, const Sizes& key_sizes_,
                        const HashMethodContextPtr&, const Sizes& offsets_ )
            : Base(key_columns), key_sizes(key_sizes_), keys_size(key_columns.size()), offsets(offsets_) {}

    ALWAYS_INLINE Key get_key_holder(size_t row, Arena&) const {
        if constexpr (has_nullable_keys_) {
            auto bitmap = Base::create_bitmap(row);
            return pack_fixed<Key>(row, keys_size, Base::get_actual_columns(), key_sizes, offsets, bitmap);
        } else {
            return pack_fixed<Key>(row, keys_size, Base::get_actual_columns(), key_sizes, offsets);
        }
    }
};

template <typename SingleColumnMethod, typename Mapped, bool use_cache>
struct HashMethodSingleLowNullableColumn : public SingleColumnMethod {
    using Base = SingleColumnMethod;

    static constexpr bool has_mapped = !std::is_same<Mapped, void>::value;
    using EmplaceResult = columns_hashing_impl::EmplaceResultImpl<Mapped>;
    using FindResult = columns_hashing_impl::FindResultImpl<Mapped>;

    static HashMethodContextPtr createContext(const HashMethodContext::Settings & settings) {
        return nullptr;
    }

    ColumnRawPtrs key_columns;

    static const ColumnRawPtrs get_nested_column(const IColumn* col) {
        auto* nullable = check_and_get_column<ColumnNullable>(*col);
        DCHECK(nullable != nullptr);
        const auto nested_col = nullable->get_nested_column_ptr().get();
        return {nested_col};
    }

    HashMethodSingleLowNullableColumn(
            const ColumnRawPtrs & key_columns_nullable, const Sizes & key_sizes, const HashMethodContextPtr & context, const Sizes& offsets_ = {})
        : Base(get_nested_column(key_columns_nullable[0]), key_sizes, context, offsets_), key_columns(key_columns_nullable) {
    }

    template <typename Data>
    ALWAYS_INLINE EmplaceResult emplace_key(Data & data, size_t row, Arena & pool) {
        if (key_columns[0]->is_null_at(row)) {
            bool has_null_key = data.has_null_key_data();
            data.has_null_key_data() = true;

            if constexpr (has_mapped)
                return EmplaceResult(data.get_null_key_data(), data.get_null_key_data(), !has_null_key);
            else
                return EmplaceResult(!has_null_key);
        }

        auto key_holder = Base::get_key_holder(row, pool);

        bool inserted = false;
        typename Data::LookupResult it;
        data.emplace(key_holder, it, inserted);

        if constexpr (has_mapped) {
            auto & mapped = *lookup_result_get_mapped(it);
            if (inserted) {
                new (&mapped) Mapped();
            }
            return EmplaceResult(mapped, mapped, inserted);
        }
        else
            return EmplaceResult(inserted);
    }
};

} // namespace ColumnsHashing
} // namespace doris::vectorized
