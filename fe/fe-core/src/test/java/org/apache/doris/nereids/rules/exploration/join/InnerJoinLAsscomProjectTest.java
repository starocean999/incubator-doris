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

package org.apache.doris.nereids.rules.exploration.join;

import org.apache.doris.common.Pair;
import org.apache.doris.nereids.trees.expressions.Add;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.Cast;
import org.apache.doris.nereids.trees.expressions.EqualTo;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.GreaterThan;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.NamedExpressionUtil;
import org.apache.doris.nereids.trees.expressions.Not;
import org.apache.doris.nereids.trees.expressions.functions.scalar.Abs;
import org.apache.doris.nereids.trees.expressions.functions.scalar.Substring;
import org.apache.doris.nereids.trees.expressions.literal.Literal;
import org.apache.doris.nereids.trees.expressions.literal.StringLiteral;
import org.apache.doris.nereids.trees.plans.JoinType;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.types.IntegerType;
import org.apache.doris.nereids.util.LogicalPlanBuilder;
import org.apache.doris.nereids.util.MemoTestUtils;
import org.apache.doris.nereids.util.PatternMatchSupported;
import org.apache.doris.nereids.util.PlanChecker;
import org.apache.doris.nereids.util.PlanConstructor;
import org.apache.doris.qe.ConnectContext;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

class InnerJoinLAsscomProjectTest implements PatternMatchSupported {
    private LogicalOlapScan scan1;
    private LogicalOlapScan scan2;
    private LogicalOlapScan scan3;

    @BeforeEach
    public void beforeEach() throws Exception {
        ConnectContext.remove();
        NamedExpressionUtil.clear();
        scan1 = PlanConstructor.newLogicalOlapScan(0, "t1", 0);
        scan2 = PlanConstructor.newLogicalOlapScan(1, "t2", 0);
        scan3 = PlanConstructor.newLogicalOlapScan(2, "t3", 0);
    }

    @Test
    void testJoinLAsscomProject() {
        /*
         * Star-Join
         * t1 -- t2
         * |
         * t3
         * <p>
         *     t1.id=t3.id               t1.id=t2.id
         *       topJoin                  newTopJoin
         *       /     \                   /     \
         *    project   t3           project    project
         * t1.id=t2.id             t1.id=t3.id    t2
         * bottomJoin       -->   newBottomJoin
         *   /    \                   /    \
         * t1      t2               t1      t3
         */
        LogicalPlan plan = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, Pair.of(0, 0))
                .project(ImmutableList.of(0, 1, 2))
                .join(scan3, JoinType.INNER_JOIN, Pair.of(1, 1))
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), plan)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .printlnExploration()
                .matchesExploration(
                        logicalJoin(
                                logicalJoin(
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t1")),
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t3"))
                                ),
                                logicalProject(
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t2"))
                                ).when(project -> project.getProjects().size() == 1)
                        )
                );
    }

    @Test
    void testAlias() {
        LogicalPlan plan = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, Pair.of(0, 0))
                .alias(ImmutableList.of(0, 2), ImmutableList.of("t1.id", "t2.id"))
                .join(scan3, JoinType.INNER_JOIN, Pair.of(0, 0))
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), plan)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .matchesExploration(
                        logicalJoin(
                                logicalProject(
                                        logicalJoin(
                                                logicalOlapScan().when(scan -> scan.getTable().getName().equals("t1")),
                                                logicalOlapScan().when(scan -> scan.getTable().getName().equals("t3"))
                                        )
                                ).when(project -> project.getProjects().size() == 3), // t1.id Add t3.id, t3.name
                                logicalProject(
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t2"))
                                ).when(project -> project.getProjects().size() == 1)
                        )
                );
    }

    @Test
    void testAliasTopMultiHashJoin() {
        LogicalPlan plan = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, Pair.of(0, 0)) // t1.id=t2.id
                .alias(ImmutableList.of(0, 2), ImmutableList.of("t1.id", "t2.id"))
                // t1.id=t3.id t2.id = t3.id
                .join(scan3, JoinType.INNER_JOIN, ImmutableList.of(Pair.of(0, 0), Pair.of(1, 0)))
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), plan)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .printlnOrigin()
                .matchesExploration(
                        logicalJoin(
                                logicalProject(
                                        logicalJoin(
                                                logicalOlapScan().when(scan -> scan.getTable().getName().equals("t1")),
                                                logicalOlapScan().when(scan -> scan.getTable().getName().equals("t3"))
                                        ).when(join -> join.getHashJoinConjuncts().size() == 1)
                                ).when(project -> project.getProjects().size() == 3), // t1.id Add t3.id, t3.name
                                logicalProject(
                                        logicalOlapScan().when(scan -> scan.getTable().getName().equals("t2"))
                                ).when(project -> project.getProjects().size() == 1)
                        ).when(join -> join.getHashJoinConjuncts().size() == 2)
                );
    }

    @Test
    public void testHashAndOther() {
        // Alias (scan1 join scan2 on scan1.id=scan2.id and scan1.name>scan2.name);
        List<Expression> bottomHashJoinConjunct = ImmutableList.of(
                new EqualTo(scan1.getOutput().get(0), scan2.getOutput().get(0)));
        List<Expression> bottomOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(scan1.getOutput().get(1), scan2.getOutput().get(1)));
        LogicalPlan bottomJoin = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, bottomHashJoinConjunct, bottomOtherJoinConjunct)
                .alias(ImmutableList.of(0, 1, 2, 3), ImmutableList.of("t1.id", "t1.name", "t2.id", "t2.name"))
                .build();

        List<Expression> topHashJoinConjunct = ImmutableList.of(
                new EqualTo(bottomJoin.getOutput().get(0), scan3.getOutput().get(0)),
                new EqualTo(bottomJoin.getOutput().get(2), scan3.getOutput().get(0)));
        List<Expression> topOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(bottomJoin.getOutput().get(1), scan3.getOutput().get(1)),
                new GreaterThan(bottomJoin.getOutput().get(3), scan3.getOutput().get(1)));
        LogicalPlan topJoin = new LogicalPlanBuilder(bottomJoin)
                .join(scan3, JoinType.INNER_JOIN, topHashJoinConjunct, topOtherJoinConjunct)
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), topJoin)
                .printlnTree()
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .printlnExploration()
                .matchesExploration(
                        innerLogicalJoin(
                                logicalProject(
                                        innerLogicalJoin().when(
                                                join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                                        "[(id#0 = id#8)]")
                                                        && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                                        "[(name#1 > name#9)]"))),
                                group()
                        ).when(join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                "[(t2.id#6 = id#8), (t1.id#4 = t2.id#6)]")
                                && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                "[(t2.name#7 > name#9), (t1.name#5 > t2.name#7)]"))
                );
    }

    /**
     * <pre>
     * LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(t1.id#4 = id#8), (t2.id#6 = (t1.id#4 + id#8))], otherJoinConjuncts=[(t1.name#5 > name#9), (t2.id#6 > (t1.id#4 + id#8))] )
     * |--LogicalProject ( projects=[id#0 AS `t1.id`#4, name#1 AS `t1.name`#5, id#2 AS `t2.id`#6, name#3 AS `t2.name`#7] )
     * |  +--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(id#0 = id#2)], otherJoinConjuncts=[(name#1 > name#3)] )
     * |     |--LogicalOlapScan ( qualified=db.t1, output=[id#0, name#1], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * |     +--LogicalOlapScan ( qualified=db.t2, output=[id#2, name#3], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * +--LogicalOlapScan ( qualified=db.t3, output=[id#8, name#9], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * -----------------------------
     * LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(t2.id#6 = (t1.id#4 + id#8)), (t1.id#4 = t2.id#6)], otherJoinConjuncts=[(t2.id#6 > (t1.id#4 + id#8)), (t1.name#5 > t2.name#7)] )
     * |--LogicalProject ( projects=[id#0 AS `t1.id`#4, name#1 AS `t1.name`#5, id#8, name#9] )
     * |  +--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(id#0 = id#8)], otherJoinConjuncts=[(name#1 > name#9)] )
     * |     |--LogicalOlapScan ( qualified=db.t1, output=[id#0, name#1], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * |     +--LogicalOlapScan ( qualified=db.t3, output=[id#8, name#9], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * +--LogicalProject ( projects=[id#2 AS `t2.id`#6, name#3 AS `t2.name`#7] )
     *    +--LogicalOlapScan ( qualified=db.t2, output=[id#2, name#3], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * </pre>
     */
    @Test
    public void testComplexConjuncts() {
        // Alias (scan1 join scan2 on scan1.id=scan2.id and scan1.name>scan2.name);
        List<Expression> bottomHashJoinConjunct = ImmutableList.of(
                new EqualTo(scan1.getOutput().get(0), scan2.getOutput().get(0)));
        List<Expression> bottomOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(scan1.getOutput().get(1), scan2.getOutput().get(1)));
        LogicalPlan bottomJoin = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, bottomHashJoinConjunct, bottomOtherJoinConjunct)
                .alias(ImmutableList.of(0, 1, 2, 3), ImmutableList.of("t1.id", "t1.name", "t2.id", "t2.name"))
                .build();

        List<Expression> topHashJoinConjunct = ImmutableList.of(
                new EqualTo(bottomJoin.getOutput().get(0), scan3.getOutput().get(0)),
                new EqualTo(bottomJoin.getOutput().get(2),
                        new Add(bottomJoin.getOutput().get(0), scan3.getOutput().get(0))));
        List<Expression> topOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(bottomJoin.getOutput().get(1), scan3.getOutput().get(1)),
                new GreaterThan(bottomJoin.getOutput().get(2),
                        new Add(bottomJoin.getOutput().get(0), scan3.getOutput().get(0))));
        LogicalPlan topJoin = new LogicalPlanBuilder(bottomJoin)
                .join(scan3, JoinType.INNER_JOIN, topHashJoinConjunct, topOtherJoinConjunct)
                .build();

        // test for no exception
        PlanChecker.from(MemoTestUtils.createConnectContext(), topJoin)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .matchesExploration(
                        innerLogicalJoin(
                                logicalProject(
                                        innerLogicalJoin().when(
                                                join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                                        "[(id#0 = id#8)]")
                                                        && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                                        "[(name#1 > name#9)]"))),
                                group()
                        ).when(join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                "[(t2.id#6 = (t1.id#4 + id#8)), (t1.id#4 = t2.id#6)]")
                                && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                "[(t2.id#6 > (t1.id#4 + id#8)), (t1.name#5 > t2.name#7)]"))
                );
    }

    /**
     * <pre>
     * LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(t1.id#4 = id#8), (t2.id#6 = (t1.id#4 + id#8))], otherJoinConjuncts=[(t1.name#5 > name#9), ( not (substring(t1.name#5, CAST('1' AS INT), CAST('3' AS INT)) = 'abc'))] )
     * |--LogicalProject ( projects=[id#0 AS `t1.id`#4, name#1 AS `t1.name`#5, id#2 AS `t2.id`#6, name#3 AS `t2.name`#7] )
     * |  +--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(id#0 = id#2)], otherJoinConjuncts=[(name#1 > name#3)] )
     * |     |--LogicalOlapScan ( qualified=db.t1, output=[id#0, name#1], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * |     +--LogicalOlapScan ( qualified=db.t2, output=[id#2, name#3], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * +--LogicalOlapScan ( qualified=db.t3, output=[id#8, name#9], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * -----------------------------
     * LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(t2.id#6 = (t1.id#4 + id#8)), (t1.id#4 = t2.id#6)], otherJoinConjuncts=[(t1.name#5 > t2.name#7)] )
     * |--LogicalProject ( projects=[id#0 AS `t1.id`#4, name#1 AS `t1.name`#5, id#8, name#9] )
     * |  +--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(id#0 = id#8)], otherJoinConjuncts=[(name#1 > name#9), ( not (substring(name#1, CAST('1' AS INT), CAST('3' AS INT)) = 'abc'))] )
     * |     |--LogicalOlapScan ( qualified=db.t1, output=[id#0, name#1], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * |     +--LogicalOlapScan ( qualified=db.t3, output=[id#8, name#9], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * +--LogicalProject ( projects=[id#2 AS `t2.id`#6, name#3 AS `t2.name`#7] )
     *    +--LogicalOlapScan ( qualified=db.t2, output=[id#2, name#3], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * </pre>
     */
    @Test
    public void testComplexConjunctsWithSubString() {
        // Alias (scan1 join scan2 on scan1.id=scan2.id and scan1.name>scan2.name);
        List<Expression> bottomHashJoinConjunct = ImmutableList.of(
                new EqualTo(scan1.getOutput().get(0), scan2.getOutput().get(0)));
        List<Expression> bottomOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(scan1.getOutput().get(1), scan2.getOutput().get(1)));
        LogicalPlan bottomJoin = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, bottomHashJoinConjunct, bottomOtherJoinConjunct)
                .alias(ImmutableList.of(0, 1, 2, 3), ImmutableList.of("t1.id", "t1.name", "t2.id", "t2.name"))
                .build();

        List<Expression> topHashJoinConjunct = ImmutableList.of(
                new EqualTo(bottomJoin.getOutput().get(0), scan3.getOutput().get(0)),
                new EqualTo(bottomJoin.getOutput().get(2),
                        new Add(bottomJoin.getOutput().get(0), scan3.getOutput().get(0))));
        // substring(t3.name, 5, 10) != '123456'
        List<Expression> topOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(bottomJoin.getOutput().get(1), scan3.getOutput().get(1)),
                new Not(new EqualTo(new Substring(bottomJoin.getOutput().get(1),
                        new Cast(new StringLiteral("1"), IntegerType.INSTANCE),
                        new Cast(new StringLiteral("3"), IntegerType.INSTANCE)),
                        Literal.of("abc"))));
        LogicalPlan topJoin = new LogicalPlanBuilder(bottomJoin)
                .join(scan3, JoinType.INNER_JOIN, topHashJoinConjunct, topOtherJoinConjunct)
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), topJoin)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .matchesExploration(
                        innerLogicalJoin(
                                logicalProject(
                                        innerLogicalJoin().when(
                                                join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                                        "[(id#0 = id#8)]")
                                                        && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                                        "[(name#1 > name#9), ( not (substring(name#1, cast('1' as INT), cast('3' as INT)) = 'abc'))]"))),
                                group()
                        ).when(join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                "[(t2.id#6 = (t1.id#4 + id#8)), (t1.id#4 = t2.id#6)]")
                                && Objects.equals(join.getOtherJoinConjuncts().toString(), "[(t1.name#5 > t2.name#7)]"))
                );
    }

    /**
     * <pre>
     * LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(t1.id#4 = id#8), ((t1.id#4 + t1.name#5) = name#9)], otherJoinConjuncts=[((t1.id#4 + t1.name#5) > name#9)] )
     * |--LogicalProject ( projects=[id#0 AS `t1.id`#4, name#1 AS `t1.name`#5, id#2 AS `t2.id`#6, name#3 AS `t2.name`#7] )
     * |  +--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(id#0 = id#2)], otherJoinConjuncts=[(name#1 > name#3)] )
     * |     |--LogicalOlapScan ( qualified=db.t1, output=[id#0, name#1], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * |     +--LogicalOlapScan ( qualified=db.t2, output=[id#2, name#3], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * +--LogicalOlapScan ( qualified=db.t3, output=[id#8, name#9], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * -----------------------------
     * LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(t1.id#4 = t2.id#6)], otherJoinConjuncts=[(t1.name#5 > t2.name#7)] )
     * |--LogicalProject ( projects=[id#0 AS `t1.id`#4, name#1 AS `t1.name`#5, id#8, name#9] )
     * |  +--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(id#0 = id#8), ((id#0 + name#1) = name#9)], otherJoinConjuncts=[((id#0 + name#1) > name#9)] )
     * |     |--LogicalOlapScan ( qualified=db.t1, output=[id#0, name#1], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * |     +--LogicalOlapScan ( qualified=db.t3, output=[id#8, name#9], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * +--LogicalProject ( projects=[id#2 AS `t2.id`#6, name#3 AS `t2.name`#7] )
     *    +--LogicalOlapScan ( qualified=db.t2, output=[id#2, name#3], candidateIndexIds=[], selectedIndexId=-1, preAgg=ON )
     * </pre>
     */
    @Test
    public void testComplexConjunctsAndAlias() {
        // Alias (scan1 join scan2 on scan1.id=scan2.id and scan1.name>scan2.name);
        List<Expression> bottomHashJoinConjunct = ImmutableList.of(
                new EqualTo(scan1.getOutput().get(0), scan2.getOutput().get(0)));
        List<Expression> bottomOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(scan1.getOutput().get(1), scan2.getOutput().get(1)));
        LogicalPlan bottomJoin = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, bottomHashJoinConjunct, bottomOtherJoinConjunct)
                .alias(ImmutableList.of(0, 1, 2, 3), ImmutableList.of("t1.id", "t1.name", "t2.id", "t2.name"))
                .build();

        List<Expression> topHashJoinConjunct = ImmutableList.of(
                new EqualTo(bottomJoin.getOutput().get(0), scan3.getOutput().get(0)),
                new EqualTo(new Add(bottomJoin.getOutput().get(0), bottomJoin.getOutput().get(1)),
                        scan3.getOutput().get(1)));
        List<Expression> topOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(new Add(bottomJoin.getOutput().get(0), bottomJoin.getOutput().get(1)),
                        scan3.getOutput().get(1)));
        LogicalPlan topJoin = new LogicalPlanBuilder(bottomJoin)
                .join(scan3, JoinType.INNER_JOIN, topHashJoinConjunct, topOtherJoinConjunct)
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), topJoin)
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .matchesExploration(
                        innerLogicalJoin(
                                logicalProject(
                                        innerLogicalJoin().when(
                                                join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                                        "[(id#0 = id#8), ((id#0 + name#1) = name#9)]")
                                                        && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                                        "[((id#0 + name#1) > name#9)]"))),
                                group()
                        ).when(join -> Objects.equals(join.getHashJoinConjuncts().toString(), "[(t1.id#4 = t2.id#6)]")
                                && Objects.equals(join.getOtherJoinConjuncts().toString(), "[(t1.name#5 > t2.name#7)]"))
                )
                .printlnExploration();
    }

    /**
     * <pre>
     * LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(abs(id#0)#4 = id#8), ((abs(id#0)#4 + t1.name#5) = name#9)], otherJoinConjuncts=[((t1.id#4 + t1.name#5) > name#9)] )
     * |--LogicalProject ( projects=[abs(id#0) AS `abs(id#0)`#4, name#1 AS `t1.name`#5, id#2 AS `t2.id`#6, name#3 AS `t2.name`#7] )
     * |  +--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(id#0 = id#2)], otherJoinConjuncts=[(name#1 > name#3)] )
     * |     |--LogicalOlapScan ( qualified=db.t1, output=[id#0, name#1], indexName=[index_not_selected], selectedIndexId=-1, preAgg=ON )
     * |     +--LogicalOlapScan ( qualified=db.t2, output=[id#2, name#3], indexName=[index_not_selected], selectedIndexId=-1, preAgg=ON )
     * +--LogicalOlapScan ( qualified=db.t3, output=[id#8, name#9], indexName=[index_not_selected], selectedIndexId=-1, preAgg=ON )
     * -----------------------------
     * LogicalProject ( projects=[abs(id#0)#4, t1.name#5, t2.id#6, t2.name#7, id#8, name#9], excepts=[], canEliminate=true )
     * +--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(id#0 = t2.id#6)], otherJoinConjuncts=[(t1.name#5 > t2.name#7)] )
     *    |--LogicalJoin ( type=INNER_JOIN, hashJoinConjuncts=[(abs(id#0)#4 = id#8), ((abs(id#0)#4 + t1.name#5) = name#9)], otherJoinConjuncts=[((abs(id#0)#4 + t1.name#5) > name#9)] )
     *    |  |--LogicalProject ( projects=[abs(id#0) AS `abs(id#0)`#4, name#1 AS `t1.name`#5, id#0], excepts=[], canEliminate=true )
     *    |  |  +--LogicalOlapScan ( qualified=db.t1, output=[id#0, name#1], indexName=[index_not_selected], selectedIndexId=-1, preAgg=ON )
     *    |  +--LogicalOlapScan ( qualified=db.t3, output=[id#8, name#9], indexName=[index_not_selected], selectedIndexId=-1, preAgg=ON )
     *    +--LogicalProject ( projects=[id#2 AS `t2.id`#6, name#3 AS `t2.name`#7], excepts=[], canEliminate=true )
     *       +--LogicalOlapScan ( qualified=db.t2, output=[id#2, name#3], indexName=[index_not_selected], selectedIndexId=-1, preAgg=ON )
     * </pre>
     */
    @Test
    public void testComplexConjunctsAndComplexAlias() {
        // Alias (scan1 join scan2 on scan1.id=scan2.id and scan1.name>scan2.name);
        List<Expression> bottomHashJoinConjunct = ImmutableList.of(
                new EqualTo(scan1.getOutput().get(0), scan2.getOutput().get(0)));
        List<Expression> bottomOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(scan1.getOutput().get(1), scan2.getOutput().get(1)));
        LogicalPlan bottomJoin = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, bottomHashJoinConjunct, bottomOtherJoinConjunct)
                .build();
        List<NamedExpression> projectExprs = Lists.newArrayList();
        List<Integer> slotsIndex = ImmutableList.of(0, 1, 2, 3);
        List<String> alias = ImmutableList.of("abs(id#0)", "t1.name", "t2.id", "t2.name");
        projectExprs.add(new Alias(new Abs(bottomJoin.getOutput().get(slotsIndex.get(0))), alias.get(0)));
        for (int i = 1; i < slotsIndex.size(); i++) {
            projectExprs.add(new Alias(bottomJoin.getOutput().get(slotsIndex.get(i)), alias.get(i)));
        }
        LogicalProject<LogicalPlan> leftProject = new LogicalProject<>(projectExprs, bottomJoin);

        List<Expression> topHashJoinConjunct = ImmutableList.of(
                new EqualTo(leftProject.getOutput().get(0), scan3.getOutput().get(0)),
                new EqualTo(new Add(leftProject.getOutput().get(0), leftProject.getOutput().get(1)),
                        scan3.getOutput().get(1)));
        List<Expression> topOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(new Add(leftProject.getOutput().get(0), leftProject.getOutput().get(1)),
                        scan3.getOutput().get(1)));
        LogicalPlan topJoin = new LogicalPlanBuilder(leftProject)
                .join(scan3, JoinType.INNER_JOIN, topHashJoinConjunct, topOtherJoinConjunct)
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), topJoin)
                .printlnOrigin()
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .printlnExploration()
                .matchesExploration(
                        logicalProject(
                            innerLogicalJoin(
                                            innerLogicalJoin().when(
                                                    join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                                            "[(abs(id#0)#4 = id#8), ((abs(id#0)#4 + t1.name#5) = name#9)]")
                                                            && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                                            "[((abs(id#0)#4 + t1.name#5) > name#9)]")),
                                    group()
                            ).when(join -> Objects.equals(join.getHashJoinConjuncts().toString(), "[(id#0 = t2.id#6)]")
                                    && Objects.equals(join.getOtherJoinConjuncts().toString(), "[(t1.name#5 > t2.name#7)]")))
                );
    }

    @Test
    public void testComplexConjunctsAndComplexAliasForB() {
        List<Expression> bottomHashJoinConjunct = ImmutableList.of(
                new EqualTo(scan1.getOutput().get(0), scan2.getOutput().get(0)));
        List<Expression> bottomOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(scan1.getOutput().get(1), scan2.getOutput().get(1)));
        LogicalPlan bottomJoin = new LogicalPlanBuilder(scan1)
                .join(scan2, JoinType.INNER_JOIN, bottomHashJoinConjunct, bottomOtherJoinConjunct)
                .build();
        List<NamedExpression> projectExprs = Lists.newArrayList();
        List<Integer> slotsIndex = ImmutableList.of(0, 1, 2, 3);
        List<String> alias = ImmutableList.of("id#0", "t1.name", "abs(t2.id)", "t2.name");
        projectExprs.add(new Alias(new Abs(bottomJoin.getOutput().get(slotsIndex.get(2))), alias.get(2)));
        for (int i = 0; i < slotsIndex.size(); i++) {
            if (i != 2) {
                projectExprs.add(new Alias(bottomJoin.getOutput().get(slotsIndex.get(i)), alias.get(i)));
            }
        }
        LogicalProject<LogicalPlan> leftProject = new LogicalProject<>(projectExprs, bottomJoin);

        List<Expression> topHashJoinConjunct = ImmutableList.of(
                new EqualTo(leftProject.getOutput().get(0), scan3.getOutput().get(0)),
                new EqualTo(new Add(leftProject.getOutput().get(1), leftProject.getOutput().get(1)),
                        scan3.getOutput().get(1)));
        List<Expression> topOtherJoinConjunct = ImmutableList.of(
                new GreaterThan(new Add(leftProject.getOutput().get(1), leftProject.getOutput().get(1)),
                        scan3.getOutput().get(1)));
        LogicalPlan topJoin = new LogicalPlanBuilder(leftProject)
                .join(scan3, JoinType.INNER_JOIN, topHashJoinConjunct, topOtherJoinConjunct)
                .build();

        PlanChecker.from(MemoTestUtils.createConnectContext(), topJoin)
                .printlnOrigin()
                .applyExploration(InnerJoinLAsscomProject.INSTANCE.build())
                .printlnExploration()
                .matchesExploration(
                        logicalProject(
                                innerLogicalJoin(logicalProject(
                                                innerLogicalJoin().when(
                                                        join -> Objects.equals(join.getHashJoinConjuncts().toString(),
                                                                "[((id#0 + id#0) = name#9)]")
                                                                && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                                                "[((id#0 + id#0) > name#9)]"))),
                                        group()
                                ).when(join ->
                                        Objects.equals(join.getHashJoinConjuncts().toString(), "[(abs(t2.id)#4 = id#8), (id#0#5 = id#2)]")
                                                && Objects.equals(join.getOtherJoinConjuncts().toString(),
                                                "[(t1.name#6 > t2.name#7)]")))
                );
    }
}
