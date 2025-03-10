/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.community.dialect;

import java.util.List;
import java.util.function.Consumer;

import org.hibernate.LockMode;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.JdbcMappingContainer;
import org.hibernate.query.sqm.ComparisonOperator;
import org.hibernate.query.sqm.FetchClauseType;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.spi.AbstractSqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlSelection;
import org.hibernate.sql.ast.tree.MutationStatement;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.ast.tree.delete.DeleteStatement;
import org.hibernate.sql.ast.tree.expression.CaseSearchedExpression;
import org.hibernate.sql.ast.tree.expression.CaseSimpleExpression;
import org.hibernate.sql.ast.tree.expression.ColumnReference;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.Literal;
import org.hibernate.sql.ast.tree.expression.SqlTuple;
import org.hibernate.sql.ast.tree.expression.Summarization;
import org.hibernate.sql.ast.tree.from.QueryPartTableReference;
import org.hibernate.sql.ast.tree.from.TableGroup;
import org.hibernate.sql.ast.tree.from.TableGroupJoin;
import org.hibernate.sql.ast.tree.from.TableReferenceJoin;
import org.hibernate.sql.ast.tree.insert.InsertSelectStatement;
import org.hibernate.sql.ast.tree.predicate.BooleanExpressionPredicate;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.select.QueryGroup;
import org.hibernate.sql.ast.tree.select.QueryPart;
import org.hibernate.sql.ast.tree.select.QuerySpec;
import org.hibernate.sql.ast.tree.select.SelectStatement;
import org.hibernate.sql.ast.tree.update.UpdateStatement;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.type.SqlTypes;

/**
 * A SQL AST translator for DB2.
 *
 * @author Christian Beikov
 */
public class DB2LegacySqlAstTranslator<T extends JdbcOperation> extends AbstractSqlAstTranslator<T> {

	// We have to track whether we are in a later query for applying lateral during window emulation
	private boolean inLateral;

	public DB2LegacySqlAstTranslator(SessionFactoryImplementor sessionFactory, Statement statement) {
		super( sessionFactory, statement );
	}

	@Override
	protected boolean needsRecursiveKeywordInWithClause() {
		return false;
	}

	@Override
	protected boolean supportsWithClauseInSubquery() {
		return false;
	}

	@Override
	protected void renderTableReferenceJoins(TableGroup tableGroup) {
		// When we are in a recursive CTE, we can't render joins on DB2...
		// See https://modern-sql.com/feature/with-recursive/db2/error-345-state-42836
		if ( isInRecursiveQueryPart() ) {
			final List<TableReferenceJoin> joins = tableGroup.getTableReferenceJoins();
			if ( joins == null || joins.isEmpty() ) {
				return;
			}

			for ( TableReferenceJoin tableJoin : joins ) {
				switch ( tableJoin.getJoinType() ) {
					case CROSS:
					case INNER:
						break;
					default:
						throw new UnsupportedOperationException( "Can't emulate '" + tableJoin.getJoinType().getText() + "join' in a DB2 recursive query part" );
				}
				appendSql( COMA_SEPARATOR_CHAR );

				renderNamedTableReference( tableJoin.getJoinedTableReference(), LockMode.NONE );

				if ( tableJoin.getPredicate() != null && !tableJoin.getPredicate().isEmpty() ) {
					addAdditionalWherePredicate( tableJoin.getPredicate() );
				}
			}
		}
		else {
			super.renderTableReferenceJoins( tableGroup );
		}
	}

	@Override
	protected void renderTableGroupJoin(TableGroupJoin tableGroupJoin, List<TableGroupJoin> tableGroupJoinCollector) {
		if ( isInRecursiveQueryPart() ) {
			switch ( tableGroupJoin.getJoinType() ) {
				case CROSS:
				case INNER:
					break;
				default:
					throw new UnsupportedOperationException( "Can't emulate '" + tableGroupJoin.getJoinType().getText() + "join' in a DB2 recursive query part" );
			}
			appendSql( COMA_SEPARATOR_CHAR );

			renderTableGroup( tableGroupJoin.getJoinedGroup(), null, tableGroupJoinCollector );
			if ( tableGroupJoin.getPredicate() != null && !tableGroupJoin.getPredicate().isEmpty() ) {
				addAdditionalWherePredicate( tableGroupJoin.getPredicate() );
			}
		}
		else {
			super.renderTableGroupJoin( tableGroupJoin, tableGroupJoinCollector );
		}
	}

	@Override
	protected void renderExpressionAsClauseItem(Expression expression) {
		if ( expression instanceof Predicate && getDB2Version().isBefore( 11 ) ) {
			super.renderExpressionAsClauseItem( expression );
		}
		else {
			expression.accept( this );
		}
	}

	@Override
	public void visitBooleanExpressionPredicate(BooleanExpressionPredicate booleanExpressionPredicate) {
		if ( getDB2Version().isSameOrAfter( 11 ) ) {
			final boolean isNegated = booleanExpressionPredicate.isNegated();
			if ( isNegated ) {
				appendSql( "not(" );
			}
			booleanExpressionPredicate.getExpression().accept( this );
			if ( isNegated ) {
				appendSql( CLOSE_PARENTHESIS );
			}
		}
		else {
			super.visitBooleanExpressionPredicate( booleanExpressionPredicate );
		}
	}

	// DB2 does not allow CASE expressions where all result arms contain plain parameters.
	// At least one result arm must provide some type context for inference,
	// so we cast the first result arm if we encounter this condition

	@Override
	protected void visitAnsiCaseSearchedExpression(
			CaseSearchedExpression caseSearchedExpression,
			Consumer<Expression> resultRenderer) {
		if ( getParameterRenderingMode() == SqlAstNodeRenderingMode.DEFAULT && areAllResultsParameters( caseSearchedExpression ) ) {
			final List<CaseSearchedExpression.WhenFragment> whenFragments = caseSearchedExpression.getWhenFragments();
			final Expression firstResult = whenFragments.get( 0 ).getResult();
			super.visitAnsiCaseSearchedExpression(
					caseSearchedExpression,
					e -> {
						if ( e == firstResult ) {
							renderCasted( e );
						}
						else {
							resultRenderer.accept( e );
						}
					}
			);
		}
		else {
			super.visitAnsiCaseSearchedExpression( caseSearchedExpression, resultRenderer );
		}
	}

	@Override
	protected void visitAnsiCaseSimpleExpression(
			CaseSimpleExpression caseSimpleExpression,
			Consumer<Expression> resultRenderer) {
		if ( getParameterRenderingMode() == SqlAstNodeRenderingMode.DEFAULT && areAllResultsParameters( caseSimpleExpression ) ) {
			final List<CaseSimpleExpression.WhenFragment> whenFragments = caseSimpleExpression.getWhenFragments();
			final Expression firstResult = whenFragments.get( 0 ).getResult();
			super.visitAnsiCaseSimpleExpression(
					caseSimpleExpression,
					e -> {
						if ( e == firstResult ) {
							renderCasted( e );
						}
						else {
							resultRenderer.accept( e );
						}
					}
			);
		}
		else {
			super.visitAnsiCaseSimpleExpression( caseSimpleExpression, resultRenderer );
		}
	}

	@Override
	protected String getForUpdate() {
		return " for read only with rs use and keep update locks";
	}

	@Override
	protected String getForShare(int timeoutMillis) {
		return " for read only with rs use and keep share locks";
	}

	@Override
	protected String getSkipLocked() {
		return " skip locked data";
	}

	protected boolean shouldEmulateFetchClause(QueryPart queryPart) {
		// Check if current query part is already row numbering to avoid infinite recursion
		if ( getQueryPartForRowNumbering() == queryPart ) {
			return false;
		}
		// Percent fetches or ties fetches aren't supported in DB2
		if ( useOffsetFetchClause( queryPart ) && !isRowsOnlyFetchClauseType( queryPart ) ) {
			return true;
		}
		// According to LegacyDB2LimitHandler, variable limit also isn't supported before 11.1
		return getDB2Version().isBefore( 11, 1 )
				&& queryPart.getFetchClauseExpression() != null
				&& !( queryPart.getFetchClauseExpression() instanceof Literal );
	}

	protected boolean supportsOffsetClause() {
		return getDB2Version().isSameOrAfter( 11, 1 );
	}

	@Override
	public void visitQueryPartTableReference(QueryPartTableReference tableReference) {
		final boolean oldLateral = inLateral;
		inLateral = tableReference.isLateral();
		super.visitQueryPartTableReference( tableReference );
		inLateral = oldLateral;
	}

	@Override
	public void visitSelectStatement(SelectStatement statement) {
		if ( getQueryPartForRowNumbering() == statement.getQueryPart() && inLateral ) {
			appendSql( "lateral " );
		}
		super.visitSelectStatement( statement );
	}

	@Override
	protected void emulateFetchOffsetWithWindowFunctionsVisitQueryPart(QueryPart queryPart) {
		if ( inLateral ) {
			appendSql( "lateral " );
			final boolean oldLateral = inLateral;
			inLateral = false;
			super.emulateFetchOffsetWithWindowFunctionsVisitQueryPart( queryPart );
			inLateral = oldLateral;
		}
		else {
			super.emulateFetchOffsetWithWindowFunctionsVisitQueryPart( queryPart );
		}
	}

	@Override
	public void visitQueryGroup(QueryGroup queryGroup) {
		final boolean emulateFetchClause = shouldEmulateFetchClause( queryGroup );
		if ( emulateFetchClause ||
				getQueryPartForRowNumbering() != queryGroup && !supportsOffsetClause() && hasOffset( queryGroup ) ) {
			emulateFetchOffsetWithWindowFunctions( queryGroup, true );
		}
		else {
			super.visitQueryGroup( queryGroup );
		}
	}

	@Override
	public void visitQuerySpec(QuerySpec querySpec) {
		final boolean emulateFetchClause = shouldEmulateFetchClause( querySpec );
		if ( emulateFetchClause ||
				getQueryPartForRowNumbering() != querySpec && !supportsOffsetClause() && hasOffset( querySpec ) ) {
			emulateFetchOffsetWithWindowFunctions( querySpec, true );
		}
		else {
			super.visitQuerySpec( querySpec );
		}
	}

	@Override
	public void visitOffsetFetchClause(QueryPart queryPart) {
		if ( !isRowNumberingCurrentQueryPart() ) {
			if ( supportsOffsetClause() || !hasOffset( queryPart ) ) {
				renderOffsetFetchClause( queryPart, true );
			}
			else if ( queryPart.isRoot() && hasLimit() ) {
				renderFetch( getLimitParameter(), null, FetchClauseType.ROWS_ONLY );
			}
			else if ( queryPart.getFetchClauseExpression() != null ) {
				renderFetch( queryPart.getFetchClauseExpression(), null, queryPart.getFetchClauseType() );
			}
		}
	}

	@Override
	protected void renderOffsetExpression(Expression offsetExpression) {
		if ( supportsParameterOffsetFetchExpression() ) {
			super.renderOffsetExpression( offsetExpression );
		}
		else {
			renderExpressionAsLiteral( offsetExpression, getJdbcParameterBindings() );
		}
	}

	@Override
	protected void renderFetchExpression(Expression fetchExpression) {
		if ( supportsParameterOffsetFetchExpression() ) {
			super.renderFetchExpression( fetchExpression );
		}
		else {
			renderExpressionAsLiteral( fetchExpression, getJdbcParameterBindings() );
		}
	}

	@Override
	protected void visitDeleteStatementOnly(DeleteStatement statement) {
		final boolean closeWrapper = renderReturningClause( statement );
		super.visitDeleteStatementOnly( statement );
		if ( closeWrapper ) {
			appendSql( ')' );
		}
	}

	@Override
	protected void visitUpdateStatementOnly(UpdateStatement statement) {
		final boolean closeWrapper = renderReturningClause( statement );
		super.visitUpdateStatementOnly( statement );
		if ( closeWrapper ) {
			appendSql( ')' );
		}
	}

	@Override
	protected void visitInsertStatementOnly(InsertSelectStatement statement) {
		final boolean closeWrapper = renderReturningClause( statement );
		super.visitInsertStatementOnly( statement );
		if ( closeWrapper ) {
			appendSql( ')' );
		}
	}

	protected boolean renderReturningClause(MutationStatement statement) {
		final List<ColumnReference> returningColumns = statement.getReturningColumns();
		final int size = returningColumns.size();
		if ( size == 0 ) {
			return false;
		}
		appendSql( "select " );
		String separator = "";
		for ( int i = 0; i < size; i++ ) {
			appendSql( separator );
			appendSql( returningColumns.get( i ).getColumnExpression() );
			separator = ",";
		}
		if ( statement instanceof DeleteStatement ) {
			appendSql( " from old table (" );
		}
		else {
			appendSql( " from final table (" );
		}
		return true;
	}

	@Override
	protected void renderComparison(Expression lhs, ComparisonOperator operator, Expression rhs) {
		if ( getDB2Version().isSameOrAfter( 11, 1 ) ) {
			renderComparisonStandard( lhs, operator, rhs );
		}
		else {
			final JdbcMappingContainer lhsExpressionType = lhs.getExpressionType();
			if ( lhsExpressionType != null && lhsExpressionType.getJdbcTypeCount() == 1
					&& lhsExpressionType.getSingleJdbcMapping().getJdbcType().getDdlTypeCode() == SqlTypes.SQLXML ) {
				// In SQL Server, XMLTYPE is not "comparable", so we have to cast the two parts to varchar for this purpose
				switch ( operator ) {
					case DISTINCT_FROM:
						appendSql( "decode(" );
						appendSql( "xmlserialize(" );
						lhs.accept( this );
						appendSql( " as varchar(32672))" );
						appendSql( ',' );
						appendSql( "xmlserialize(" );
						rhs.accept( this );
						appendSql( " as varchar(32672))" );
						appendSql( ",0,1)=1" );
						return;
					case NOT_DISTINCT_FROM:
						appendSql( "decode(" );
						appendSql( "xmlserialize(" );
						lhs.accept( this );
						appendSql( " as varchar(32672))" );
						appendSql( ',' );
						appendSql( "xmlserialize(" );
						rhs.accept( this );
						appendSql( " as varchar(32672))" );
						appendSql( ",0,1)=0" );
						return;
					case EQUAL:
					case NOT_EQUAL:
						appendSql( "xmlserialize(" );
						lhs.accept( this );
						appendSql( " as varchar(32672))" );
						appendSql( operator.sqlText() );
						appendSql( "xmlserialize(" );
						rhs.accept( this );
						appendSql( " as varchar(32672))" );
						return;
					default:
						// Fall through
						break;
				}
			}
			renderComparisonEmulateDecode( lhs, operator, rhs );
		}
	}

	@Override
	protected void renderComparisonStandard(Expression lhs, ComparisonOperator operator, Expression rhs) {
		final JdbcMappingContainer lhsExpressionType = lhs.getExpressionType();
		if ( lhsExpressionType != null && lhsExpressionType.getJdbcTypeCount() == 1
				&& lhsExpressionType.getSingleJdbcMapping().getJdbcType().getDdlTypeCode() == SqlTypes.SQLXML ) {
			// In SQL Server, XMLTYPE is not "comparable", so we have to cast the two parts to varchar for this purpose
			switch ( operator ) {
				case EQUAL:
				case NOT_DISTINCT_FROM:
				case NOT_EQUAL:
				case DISTINCT_FROM:
					appendSql( "xmlserialize(" );
					lhs.accept( this );
					appendSql( " as varchar(32672))" );
					appendSql( operator.sqlText() );
					appendSql( "xmlserialize(" );
					rhs.accept( this );
					appendSql( " as varchar(32672))" );
					return;
				default:
					// Fall through
					break;
			}
		}
		super.renderComparisonStandard( lhs, operator, rhs );
	}

	@Override
	protected void renderSelectExpression(Expression expression) {
		renderSelectExpressionWithCastedOrInlinedPlainParameters( expression );
	}

	@Override
	protected void renderSelectTupleComparison(
			List<SqlSelection> lhsExpressions,
			SqlTuple tuple,
			ComparisonOperator operator) {
		emulateSelectTupleComparison( lhsExpressions, tuple.getExpressions(), operator, true );
	}

	@Override
	protected void renderPartitionItem(Expression expression) {
		if ( expression instanceof Literal ) {
			appendSql( "()" );
		}
		else if ( expression instanceof Summarization ) {
			Summarization summarization = (Summarization) expression;
			appendSql( summarization.getKind().sqlText() );
			appendSql( OPEN_PARENTHESIS );
			renderCommaSeparated( summarization.getGroupings() );
			appendSql( CLOSE_PARENTHESIS );
		}
		else {
			expression.accept( this );
		}
	}

	@Override
	protected boolean supportsRowValueConstructorSyntax() {
		return false;
	}

	@Override
	protected boolean supportsRowValueConstructorSyntaxInInList() {
		return false;
	}

	@Override
	protected boolean supportsRowValueConstructorSyntaxInQuantifiedPredicates() {
		return false;
	}

	@Override
	protected String getFromDual() {
		return " from sysibm.dual";
	}

	@Override
	protected String getFromDualForSelectOnly() {
		return getFromDual();
	}

	@Override
	protected void visitReturningColumns(List<ColumnReference> returningColumns) {
		// For DB2 we use #renderReturningClause to render a wrapper around the DML statement
	}

	public DatabaseVersion getDB2Version() {
		return this.getDialect().getVersion();
	}

	protected boolean supportsParameterOffsetFetchExpression() {
		return getDB2Version().isSameOrAfter( 11 );
	}

}
