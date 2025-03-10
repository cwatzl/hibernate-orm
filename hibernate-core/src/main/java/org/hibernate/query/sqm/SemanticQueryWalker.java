/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm;

import java.util.List;

import org.hibernate.metamodel.model.domain.internal.AnyDiscriminatorSqmPath;
import org.hibernate.query.sqm.sql.internal.SelfInterpretingSqmPath;
import org.hibernate.query.sqm.tree.cte.SqmCteContainer;
import org.hibernate.query.sqm.tree.cte.SqmCteStatement;
import org.hibernate.query.sqm.tree.delete.SqmDeleteStatement;
import org.hibernate.query.sqm.tree.domain.NonAggregatedCompositeSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmAnyValuedSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmBasicValuedSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmCorrelation;
import org.hibernate.query.sqm.tree.domain.SqmCteRoot;
import org.hibernate.query.sqm.tree.domain.SqmDerivedRoot;
import org.hibernate.query.sqm.tree.domain.SqmEmbeddedValuedSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmEntityValuedSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmFkExpression;
import org.hibernate.query.sqm.tree.domain.SqmIndexedCollectionAccessPath;
import org.hibernate.query.sqm.tree.domain.SqmMapEntryReference;
import org.hibernate.query.sqm.tree.domain.SqmElementAggregateFunction;
import org.hibernate.query.sqm.tree.domain.SqmIndexAggregateFunction;
import org.hibernate.query.sqm.tree.domain.SqmPluralPartJoin;
import org.hibernate.query.sqm.tree.domain.SqmPluralValuedSimplePath;
import org.hibernate.query.sqm.tree.domain.SqmTreatedPath;
import org.hibernate.query.sqm.tree.expression.JpaCriteriaParameter;
import org.hibernate.query.sqm.tree.expression.SqmAny;
import org.hibernate.query.sqm.tree.expression.SqmAnyDiscriminatorValue;
import org.hibernate.query.sqm.tree.expression.SqmBinaryArithmetic;
import org.hibernate.query.sqm.tree.expression.SqmByUnit;
import org.hibernate.query.sqm.tree.expression.SqmCaseSearched;
import org.hibernate.query.sqm.tree.expression.SqmCaseSimple;
import org.hibernate.query.sqm.tree.expression.SqmCastTarget;
import org.hibernate.query.sqm.tree.expression.SqmCoalesce;
import org.hibernate.query.sqm.tree.expression.SqmCollation;
import org.hibernate.query.sqm.tree.expression.SqmCollectionSize;
import org.hibernate.query.sqm.tree.expression.SqmDistinct;
import org.hibernate.query.sqm.tree.expression.SqmDurationUnit;
import org.hibernate.query.sqm.tree.expression.SqmEnumLiteral;
import org.hibernate.query.sqm.tree.expression.SqmEvery;
import org.hibernate.query.sqm.tree.expression.SqmExpression;
import org.hibernate.query.sqm.tree.expression.SqmExtractUnit;
import org.hibernate.query.sqm.tree.expression.SqmFieldLiteral;
import org.hibernate.query.sqm.tree.expression.SqmFormat;
import org.hibernate.query.sqm.tree.expression.SqmFunction;
import org.hibernate.query.sqm.tree.expression.SqmLiteral;
import org.hibernate.query.sqm.tree.expression.SqmLiteralEntityType;
import org.hibernate.query.sqm.tree.expression.SqmModifiedSubQueryExpression;
import org.hibernate.query.sqm.tree.expression.SqmNamedParameter;
import org.hibernate.query.sqm.tree.expression.SqmOver;
import org.hibernate.query.sqm.tree.expression.SqmOverflow;
import org.hibernate.query.sqm.tree.expression.SqmParameterizedEntityType;
import org.hibernate.query.sqm.tree.expression.SqmPositionalParameter;
import org.hibernate.query.sqm.tree.expression.SqmStar;
import org.hibernate.query.sqm.tree.expression.SqmSummarization;
import org.hibernate.query.sqm.tree.expression.SqmToDuration;
import org.hibernate.query.sqm.tree.expression.SqmTrimSpecification;
import org.hibernate.query.sqm.tree.expression.SqmTuple;
import org.hibernate.query.sqm.tree.expression.SqmUnaryOperation;
import org.hibernate.query.sqm.tree.expression.SqmWindow;
import org.hibernate.query.sqm.tree.from.SqmAttributeJoin;
import org.hibernate.query.sqm.tree.from.SqmCrossJoin;
import org.hibernate.query.sqm.tree.from.SqmCteJoin;
import org.hibernate.query.sqm.tree.from.SqmDerivedJoin;
import org.hibernate.query.sqm.tree.from.SqmEntityJoin;
import org.hibernate.query.sqm.tree.from.SqmFromClause;
import org.hibernate.query.sqm.tree.from.SqmRoot;
import org.hibernate.query.sqm.tree.insert.SqmInsertSelectStatement;
import org.hibernate.query.sqm.tree.insert.SqmInsertValuesStatement;
import org.hibernate.query.sqm.tree.insert.SqmValues;
import org.hibernate.query.sqm.tree.predicate.SqmBetweenPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmBooleanExpressionPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmComparisonPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmEmptinessPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmExistsPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmGroupedPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmInListPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmInSubQueryPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmJunctionPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmLikePredicate;
import org.hibernate.query.sqm.tree.predicate.SqmMemberOfPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmNegatedPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmNullnessPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmPredicate;
import org.hibernate.query.sqm.tree.predicate.SqmWhereClause;
import org.hibernate.query.sqm.tree.select.SqmDynamicInstantiation;
import org.hibernate.query.sqm.tree.select.SqmJpaCompoundSelection;
import org.hibernate.query.sqm.tree.select.SqmOrderByClause;
import org.hibernate.query.sqm.tree.select.SqmQueryGroup;
import org.hibernate.query.sqm.tree.select.SqmQuerySpec;
import org.hibernate.query.sqm.tree.select.SqmSelectClause;
import org.hibernate.query.sqm.tree.select.SqmSelectStatement;
import org.hibernate.query.sqm.tree.select.SqmSelection;
import org.hibernate.query.sqm.tree.select.SqmSortSpecification;
import org.hibernate.query.sqm.tree.select.SqmSubQuery;
import org.hibernate.query.sqm.tree.update.SqmAssignment;
import org.hibernate.query.sqm.tree.update.SqmSetClause;
import org.hibernate.query.sqm.tree.update.SqmUpdateStatement;

/**
 * Support for walking a Semantic Query Model (SQM) tree
 *
 * @author Steve Ebersole
 */
public interface SemanticQueryWalker<T> {
	T visitUpdateStatement(SqmUpdateStatement<?> statement);

	T visitSetClause(SqmSetClause setClause);

	T visitAssignment(SqmAssignment<?> assignment);

	T visitInsertSelectStatement(SqmInsertSelectStatement<?> statement);

	T visitInsertValuesStatement(SqmInsertValuesStatement<?> statement);

	T visitDeleteStatement(SqmDeleteStatement<?> statement);

	T visitSelectStatement(SqmSelectStatement<?> statement);

	T visitCteStatement(SqmCteStatement<?> sqmCteStatement);

	T visitCteContainer(SqmCteContainer consumer);


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// from-clause / domain paths

	T visitFromClause(SqmFromClause fromClause);

	T visitRootPath(SqmRoot<?> sqmRoot);

	T visitRootDerived(SqmDerivedRoot<?> sqmRoot);

	T visitRootCte(SqmCteRoot<?> sqmRoot);

	T visitCrossJoin(SqmCrossJoin<?> joinedFromElement);

	T visitPluralPartJoin(SqmPluralPartJoin<?, ?> joinedFromElement);

	T visitQualifiedEntityJoin(SqmEntityJoin<?> joinedFromElement);

	T visitQualifiedAttributeJoin(SqmAttributeJoin<?, ?> joinedFromElement);

	T visitQualifiedDerivedJoin(SqmDerivedJoin<?> joinedFromElement);

	T visitQualifiedCteJoin(SqmCteJoin<?> joinedFromElement);

	T visitBasicValuedPath(SqmBasicValuedSimplePath<?> path);

	T visitEmbeddableValuedPath(SqmEmbeddedValuedSimplePath<?> path);

	T visitAnyValuedValuedPath(SqmAnyValuedSimplePath<?> path);

	T visitNonAggregatedCompositeValuedPath(NonAggregatedCompositeSimplePath<?> path);

	T visitEntityValuedPath(SqmEntityValuedSimplePath<?> path);

	T visitPluralValuedPath(SqmPluralValuedSimplePath<?> path);

	T visitFkExpression(SqmFkExpression<?> fkExpression);

	T visitSelfInterpretingSqmPath(SelfInterpretingSqmPath<?> sqmPath);
	
	T visitIndexedPluralAccessPath(SqmIndexedCollectionAccessPath<?> path);

	T visitElementAggregateFunction(SqmElementAggregateFunction<?> path);

	T visitIndexAggregateFunction(SqmIndexAggregateFunction<?> path);

	T visitTreatedPath(SqmTreatedPath<?, ?> sqmTreatedPath);

	T visitCorrelation(SqmCorrelation<?, ?> correlation);


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// Query spec

	T visitQueryGroup(SqmQueryGroup<?> queryGroup);

	T visitQuerySpec(SqmQuerySpec<?> querySpec);

	T visitSelectClause(SqmSelectClause selectClause);

	T visitSelection(SqmSelection<?> selection);

	T visitValues(SqmValues values);

	T visitGroupByClause(List<SqmExpression<?>> groupByClauseExpressions);

	T visitHavingClause(SqmPredicate clause);

	T visitDynamicInstantiation(SqmDynamicInstantiation<?> sqmDynamicInstantiation);

	T visitJpaCompoundSelection(SqmJpaCompoundSelection<?> selection);

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// expressions - general

	T visitLiteral(SqmLiteral<?> literal);

	T visitEnumLiteral(SqmEnumLiteral<?> sqmEnumLiteral);

	T visitFieldLiteral(SqmFieldLiteral<?> sqmFieldLiteral);

	T visitTuple(SqmTuple<?> sqmTuple);

	T visitCollation(SqmCollation sqmCollate);

	T visitBinaryArithmeticExpression(SqmBinaryArithmetic<?> expression);

	T visitSubQueryExpression(SqmSubQuery<?> expression);

	T visitModifiedSubQueryExpression(SqmModifiedSubQueryExpression<?> expression);

	T visitSimpleCaseExpression(SqmCaseSimple<?, ?> expression);

	T visitSearchedCaseExpression(SqmCaseSearched<?> expression);

	T visitAny(SqmAny<?> sqmAny);

	T visitEvery(SqmEvery<?> sqmEvery);

	T visitSummarization(SqmSummarization<?> sqmSummarization);

	T visitPositionalParameterExpression(SqmPositionalParameter<?> expression);

	T visitNamedParameterExpression(SqmNamedParameter<?> expression);

	T visitJpaCriteriaParameter(JpaCriteriaParameter<?> expression);

	T visitEntityTypeLiteralExpression(SqmLiteralEntityType<?> expression);

	T visitAnyDiscriminatorTypeExpression(AnyDiscriminatorSqmPath expression);

	T visitAnyDiscriminatorTypeValueExpression(SqmAnyDiscriminatorValue expression);

	T visitParameterizedEntityTypeExpression(SqmParameterizedEntityType<?> expression);

	T visitUnaryOperationExpression(SqmUnaryOperation<?> expression);

	T visitFunction(SqmFunction<?> tSqmFunction);

	T visitExtractUnit(SqmExtractUnit<?> extractUnit);

	T visitFormat(SqmFormat sqmFormat);

	T visitCastTarget(SqmCastTarget<?> sqmCastTarget);

	T visitTrimSpecification(SqmTrimSpecification trimSpecification);

	T visitDistinct(SqmDistinct<?> distinct);

	T visitStar(SqmStar sqmStar);

	T visitOver(SqmOver<?> over);

	T visitWindow(SqmWindow widow);

	T visitOverflow(SqmOverflow<?> sqmOverflow);

	T visitCoalesce(SqmCoalesce<?> sqmCoalesce);

	T visitToDuration(SqmToDuration<?> toDuration);

	T visitByUnit(SqmByUnit sqmByUnit);

	T visitDurationUnit(SqmDurationUnit<?> durationUnit);

	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// predicates

	T visitWhereClause(SqmWhereClause whereClause);

	T visitGroupedPredicate(SqmGroupedPredicate predicate);

	T visitJunctionPredicate(SqmJunctionPredicate predicate);

	T visitComparisonPredicate(SqmComparisonPredicate predicate);

	T visitIsEmptyPredicate(SqmEmptinessPredicate predicate);

	T visitIsNullPredicate(SqmNullnessPredicate predicate);

	T visitBetweenPredicate(SqmBetweenPredicate predicate);

	T visitLikePredicate(SqmLikePredicate predicate);

	T visitMemberOfPredicate(SqmMemberOfPredicate predicate);

	T visitNegatedPredicate(SqmNegatedPredicate predicate);

	T visitInListPredicate(SqmInListPredicate<?> predicate);

	T visitInSubQueryPredicate(SqmInSubQueryPredicate<?> predicate);

	T visitBooleanExpressionPredicate(SqmBooleanExpressionPredicate predicate);

	T visitExistsPredicate(SqmExistsPredicate sqmExistsPredicate);


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// sorting

	T visitOrderByClause(SqmOrderByClause orderByClause);

	T visitSortSpecification(SqmSortSpecification sortSpecification);


	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// paging

	T visitOffsetExpression(SqmExpression<?> expression);
	T visitFetchExpression(SqmExpression<?> expression);



	// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
	// misc

	T visitPluralAttributeSizeFunction(SqmCollectionSize function);

	T visitMapEntryFunction(SqmMapEntryReference<?, ?> function);

	T visitFullyQualifiedClass(Class<?> namedClass);
}
