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

import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleType;
import org.apache.doris.nereids.rules.exploration.OneExplorationRuleFactory;
import org.apache.doris.nereids.trees.expressions.ExprId;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.plans.GroupPlan;
import org.apache.doris.nereids.trees.plans.JoinHint;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.util.ExpressionUtils;
import org.apache.doris.nereids.util.JoinUtils;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rule for change inner join LAsscom (associative and commutive).
 */
public class InnerJoinLAsscomProject extends OneExplorationRuleFactory {
    public static final InnerJoinLAsscomProject INSTANCE = new InnerJoinLAsscomProject();

    /*
     *        topJoin                   newTopJoin
     *        /     \                   /        \
     *    project    C          newLeftProject newRightProject
     *      /            ──►          /            \
     * bottomJoin                newBottomJoin      B
     *    /   \                     /   \
     *   A     B                   A     C
     */
    @Override
    public Rule build() {
        return innerLogicalJoin(logicalProject(innerLogicalJoin()), group())
                .whenNot(join -> join.hasJoinHint() || join.left().child().hasJoinHint())
                .when(topJoin -> InnerJoinLAsscom.checkReorder(topJoin, topJoin.left().child()))
                .when(join -> JoinReorderUtils.checkHyperEdgeProjectForJoin(join.left()))
                .then(topJoin -> {

                    /* ********** init ********** */
                    List<NamedExpression> projects = topJoin.left().getProjects();
                    LogicalJoin<GroupPlan, GroupPlan> bottomJoin = topJoin.left().child();
                    GroupPlan a = bottomJoin.left();
                    GroupPlan b = bottomJoin.right();
                    GroupPlan c = topJoin.right();
                    Set<Slot> cOutputSet = c.getOutputSet();
                    Set<ExprId> cOutputExprIdSet = c.getOutputExprIdSet();

                    /* ********** Split projects ********** */
                    Map<Boolean, List<NamedExpression>> map = JoinReorderUtils.splitProjection(projects, b);
                    List<NamedExpression> newLeftProjects = map.get(false);
                    List<NamedExpression> newRightProjects = map.get(true);
                    Set<ExprId> bExprIdSet = JoinReorderUtils.combineProjectAndChildExprId(b, newRightProjects);

                    /* ********** split HashConjuncts ********** */
                    Map<Boolean, List<Expression>> splitHashConjuncts = splitConjunctsWithAlias(
                            topJoin.getHashJoinConjuncts(), bottomJoin.getHashJoinConjuncts(), bExprIdSet);
                    List<Expression> newTopHashConjuncts = splitHashConjuncts.get(true);
                    List<Expression> newBottomHashConjuncts = splitHashConjuncts.get(false);
                    Preconditions.checkState(!newTopHashConjuncts.isEmpty(), "newTopHashConjuncts is empty");
                    if (newBottomHashConjuncts.size() == 0) {
                        return null;
                    }

                    /* ********** split OtherConjuncts ********** */
                    Map<Boolean, List<Expression>> splitOtherConjuncts = splitConjunctsWithAlias(
                            topJoin.getOtherJoinConjuncts(), bottomJoin.getOtherJoinConjuncts(),
                            bExprIdSet);
                    List<Expression> newTopOtherConjuncts = splitOtherConjuncts.get(true);
                    List<Expression> newBottomOtherConjuncts = splitOtherConjuncts.get(false);

                    /* ********** replace Conjuncts by projects ********** */

                    // find replace map for join conjuncts and figure out if need a new project child for A
                    Map<ExprId, Expression> replaceMapForNewTopJoin = new HashMap<>();
                    Map<ExprId, Expression> replaceMapForNewBottomJoin = new HashMap<>();
                    boolean needNewProjectForA = JoinReorderUtils.needCreateLeftBottomChildProject(
                            projects, a.getOutputExprIdSet(), replaceMapForNewTopJoin,
                            replaceMapForNewBottomJoin);

                    // replace top join conjuncts
                    newTopHashConjuncts = JoinUtils.replaceJoinConjuncts(newTopHashConjuncts,
                            replaceMapForNewTopJoin);
                    newTopOtherConjuncts = JoinUtils.replaceJoinConjuncts(newTopOtherConjuncts,
                            replaceMapForNewTopJoin);

                    // create new left and right project based on top join conjuncts
                    Map<Boolean, Set<Slot>> abOnUsedSlots = Stream.concat(
                                    newTopHashConjuncts.stream(),
                                    newTopOtherConjuncts.stream())
                            .flatMap(onExpr -> {
                                Set<Slot> usedSlotRefs = onExpr.getInputSlots();
                                return usedSlotRefs.stream();
                            })
                            .filter(slot -> !cOutputExprIdSet.contains(slot.getExprId()))
                            .collect(Collectors.partitioningBy(
                                    slot -> bExprIdSet.contains(slot.getExprId()), Collectors.toSet()));
                    Set<Slot> aUsedSlots = abOnUsedSlots.get(false);
                    Set<Slot> bUsedSlots = abOnUsedSlots.get(true);

                    JoinUtils.addSlotsUsedByOn(bUsedSlots, newRightProjects);
                    JoinUtils.addSlotsUsedByOn(aUsedSlots, newLeftProjects);

                    /* ********** new Plan ********** */
                    LogicalJoin newBottomJoin;
                    if (needNewProjectForA) {
                        /*
                         *        topJoin                         newTopJoin
                         *        /     \                         /        \
                         *    project    C                newBottomJoin  newRightProject
                         *      /            ──►                /     \      \
                         * bottomJoin              needNewProjectForA  C      B
                         *    /   \                           /
                         *   A     B                         A
                         */
                        // create a new project node as A's parent
                        newBottomJoin = new LogicalJoin<>(topJoin.getJoinType(),
                                newBottomHashConjuncts, newBottomOtherConjuncts,
                                JoinHint.NONE, JoinReorderUtils.projectOrSelf(newLeftProjects, a), c,
                                bottomJoin.getJoinReorderContext());
                    } else {
                        // replace the join conjuncts
                        newBottomHashConjuncts = JoinUtils.replaceJoinConjuncts(
                                newBottomHashConjuncts, replaceMapForNewBottomJoin);
                        newBottomOtherConjuncts = JoinUtils.replaceJoinConjuncts(
                                newBottomOtherConjuncts, replaceMapForNewBottomJoin);
                        newBottomJoin = new LogicalJoin<>(topJoin.getJoinType(),
                                newBottomHashConjuncts, newBottomOtherConjuncts,
                                JoinHint.NONE, a, c, bottomJoin.getJoinReorderContext());
                    }
                    newBottomJoin.getJoinReorderContext().setHasLAsscom(false);
                    newBottomJoin.getJoinReorderContext().setHasCommute(false);

                    // new left project should contain all output slots from C
                    if (!newLeftProjects.isEmpty()) {
                        newLeftProjects.addAll(cOutputSet);
                    }

                    Plan left = needNewProjectForA ? newBottomJoin
                            : JoinReorderUtils.projectOrSelf(newLeftProjects, newBottomJoin);
                    Plan right = JoinReorderUtils.projectOrSelf(newRightProjects, b);

                    LogicalJoin<Plan, Plan> newTopJoin = new LogicalJoin<>(bottomJoin.getJoinType(),
                            newTopHashConjuncts, newTopOtherConjuncts, JoinHint.NONE,
                            left, right, topJoin.getJoinReorderContext());
                    newTopJoin.getJoinReorderContext().setHasLAsscom(true);

                    return JoinReorderUtils.projectOrSelf(ImmutableList.copyOf(topJoin.getOutput()), newTopJoin);
                }).toRule(RuleType.LOGICAL_INNER_JOIN_LASSCOM_PROJECT);
    }

    /**
     * Split Condition into two part.
     * True: contains B.
     * False: just contains A C.
     */
    private Map<Boolean, List<Expression>> splitConjunctsWithAlias(List<Expression> topConjuncts,
            List<Expression> bottomConjuncts, Set<ExprId> bExprIdSet) {
        // top: (A B)(error) (AB C)(check project would fail), (A C) (B C) are acceptable
        // Split topJoin Condition to two part according to include B.
        Map<Boolean, List<Expression>> splitOn = topConjuncts.stream()
                .collect(Collectors.partitioningBy(topHashOn -> {
                    Set<ExprId> usedExprIds = topHashOn.getInputSlotExprIds();
                    return ExpressionUtils.isIntersecting(bExprIdSet, usedExprIds);
                }));
        // * don't include B, just include (A C)
        // we add it into newBottomJoin HashConjuncts.
        // * include B, include (B C) or (A B)
        // we add it into newTopJoin HashConjuncts.
        List<Expression> newTopHashConjuncts = splitOn.get(true);
        newTopHashConjuncts.addAll(bottomConjuncts);

        // splitOn.get(true) -> (B C) and bottomConjuncts -> (A B), belong to new top join conjunct( including B )
        // splitOn.get(false) -> (A C), belong to new bottom join conjunct( NOT including B )
        return splitOn;
    }
}
