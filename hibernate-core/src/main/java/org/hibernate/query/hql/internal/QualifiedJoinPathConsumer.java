/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.hql.internal;

import org.hibernate.metamodel.model.domain.EntityDomainType;
import org.hibernate.query.SemanticException;
import org.hibernate.query.hql.spi.DotIdentifierConsumer;
import org.hibernate.query.hql.spi.SemanticPathPart;
import org.hibernate.query.hql.spi.SqmCreationProcessingState;
import org.hibernate.query.hql.spi.SqmCreationState;
import org.hibernate.query.hql.spi.SqmPathRegistry;
import org.hibernate.query.sqm.SqmJoinable;
import org.hibernate.query.sqm.SqmPathSource;
import org.hibernate.query.sqm.spi.SqmCreationHelper;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.hibernate.query.sqm.tree.cte.SqmCteStatement;
import org.hibernate.query.sqm.tree.domain.SqmCteRoot;
import org.hibernate.query.sqm.tree.domain.SqmPath;
import org.hibernate.query.sqm.tree.domain.SqmPolymorphicRootDescriptor;
import org.hibernate.query.sqm.tree.from.SqmCteJoin;
import org.hibernate.query.sqm.tree.from.SqmEntityJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.hibernate.query.sqm.tree.from.SqmRoot;

import org.jboss.logging.Logger;

/**
 * Specialized "intermediate" SemanticPathPart for processing domain model paths.
 *
 * @author Steve Ebersole
 */
public class QualifiedJoinPathConsumer implements DotIdentifierConsumer {
	private static final Logger log = Logger.getLogger( QualifiedJoinPathConsumer.class );

	private final SqmCreationState creationState;
	private final SqmRoot<?> sqmRoot;

	private final SqmJoinType joinType;
	private final boolean fetch;
	private final String alias;

	private ConsumerDelegate delegate;
	private boolean nested;

	public QualifiedJoinPathConsumer(
			SqmRoot<?> sqmRoot,
			SqmJoinType joinType,
			boolean fetch,
			String alias,
			SqmCreationState creationState) {
		this.sqmRoot = sqmRoot;
		this.joinType = joinType;
		this.fetch = fetch;
		this.alias = alias;
		this.creationState = creationState;
	}

	public QualifiedJoinPathConsumer(
			SqmFrom<?, ?> sqmFrom,
			SqmJoinType joinType,
			boolean fetch,
			String alias,
			SqmCreationState creationState) {
		this.sqmRoot = null;
		this.joinType = joinType;
		this.fetch = fetch;
		this.alias = alias;
		this.creationState = creationState;
		this.delegate = new AttributeJoinDelegate(
				sqmFrom,
				joinType,
				fetch,
				alias,
				creationState
		);
	}

	public boolean isNested() {
		return nested;
	}

	public void setNested(boolean nested) {
		this.nested = nested;
	}

	@Override
	public SemanticPathPart getConsumedPart() {
		return delegate.getConsumedPart();
	}

	@Override
	public void consumeIdentifier(String identifier, boolean isBase, boolean isTerminal) {
		if ( isBase && delegate == null ) {
			delegate = resolveBase( identifier, !nested && isTerminal );
		}
		else {
			assert delegate != null;
			delegate.consumeIdentifier( identifier, !nested && isTerminal, !( nested && isTerminal ) );
		}
	}

	@Override
	public void consumeTreat(String entityName, boolean isTerminal) {
		assert delegate != null;
		delegate.consumeTreat( entityName, isTerminal );
	}

	private ConsumerDelegate resolveBase(String identifier, boolean isTerminal) {
		final SqmCreationProcessingState processingState = creationState.getCurrentProcessingState();
		final SqmPathRegistry pathRegistry = processingState.getPathRegistry();

		final SqmFrom<?, Object> pathRootByAlias = pathRegistry.findFromByAlias( identifier, true );
		if ( pathRootByAlias != null ) {
			// identifier is an alias (identification variable)

			if ( isTerminal ) {
				throw new SemanticException( "Cannot join to root '" + identifier + "'" );
			}

			return new AttributeJoinDelegate(
					pathRootByAlias,
					joinType,
					fetch,
					alias,
					creationState
			);
		}

		final SqmFrom<?, Object> pathRootByExposedNavigable = pathRegistry.findFromExposing( identifier );
		if ( pathRootByExposedNavigable != null ) {
			return new AttributeJoinDelegate(
					createJoin( pathRootByExposedNavigable, identifier, isTerminal ),
					joinType,
					fetch,
					alias,
					creationState
			);
		}

		// otherwise, assume we have a qualified entity name - delay resolution until we
		// process the final token

		return new ExpectingEntityJoinDelegate(
				identifier,
				isTerminal,
				sqmRoot,
				joinType,
				alias,
				fetch,
				creationState
		);
	}

	private SqmFrom<?, ?> createJoin(SqmFrom<?, Object> lhs, String identifier, boolean isTerminal) {
		return createJoin(
				lhs,
				identifier,
				joinType,
				alias,
				fetch,
				isTerminal,
				true,
				creationState
		);
	}

	private static SqmFrom<?, Object> createJoin(
			SqmFrom<?, Object> lhs,
			String name,
			SqmJoinType joinType,
			String alias,
			boolean fetch,
			boolean isTerminal,
			boolean allowReuse,
			SqmCreationState creationState) {
		//noinspection unchecked
		final SqmPathSource<Object> subPathSource = (SqmPathSource<Object>) lhs.getReferencedPathSource().getSubPathSource( name );
		if ( allowReuse && !isTerminal ) {
			for ( SqmJoin<?, ?> sqmJoin : lhs.getSqmJoins() ) {
				if ( sqmJoin.getAlias() == null && sqmJoin.getReferencedPathSource() == subPathSource ) {
					//noinspection unchecked
					return (SqmFrom<?, Object>) sqmJoin;
				}
			}
		}
		@SuppressWarnings("unchecked")
		final SqmJoin<Object, Object> join = ( (SqmJoinable<Object, Object>) subPathSource ).createSqmJoin(
				lhs,
				joinType,
				isTerminal ? alias : allowReuse ? SqmCreationHelper.IMPLICIT_ALIAS : Long.toString( System.nanoTime() ),
				fetch,
				creationState
		);
		lhs.addSqmJoin( join );
		creationState.getCurrentProcessingState().getPathRegistry().register( join );
		return join;
	}

	private interface ConsumerDelegate {
		void consumeIdentifier(String identifier, boolean isTerminal, boolean allowReuse);
		void consumeTreat(String entityName, boolean isTerminal);
		SemanticPathPart getConsumedPart();
	}

	private static class AttributeJoinDelegate implements ConsumerDelegate {
		private final SqmCreationState creationState;

		private final SqmJoinType joinType;
		private final boolean fetch;
		private final String alias;

		private SqmFrom<?, Object> currentPath;

		public AttributeJoinDelegate(
				SqmFrom<?, ?> base,
				SqmJoinType joinType,
				boolean fetch,
				String alias,
				SqmCreationState creationState) {
			this.joinType = joinType;
			this.fetch = fetch;
			this.alias = alias;
			this.creationState = creationState;

			//noinspection unchecked
			this.currentPath = (SqmFrom<?, Object>) base;
		}

		@Override
		public void consumeIdentifier(String identifier, boolean isTerminal, boolean allowReuse) {
			currentPath = createJoin(
					currentPath,
					identifier,
					joinType,
					alias,
					fetch,
					isTerminal,
					allowReuse,
					creationState
			);
		}

		@Override
		public void consumeTreat(String entityName, boolean isTerminal) {
			final EntityDomainType<Object> entityDomainType = creationState.getCreationContext().getJpaMetamodel()
					.entity( entityName );
			if ( isTerminal ) {
				currentPath = currentPath.treatAs( entityDomainType, alias );
			}
			else {
				currentPath = currentPath.treatAs( entityDomainType );
			}
			creationState.getCurrentProcessingState().getPathRegistry().register( currentPath );
		}

		@Override
		public SemanticPathPart getConsumedPart() {
			return currentPath;
		}
	}

	private static class ExpectingEntityJoinDelegate implements ConsumerDelegate {
		private final SqmCreationState creationState;
		private final SqmRoot<?> sqmRoot;

		private final SqmJoinType joinType;
		private final boolean fetch;
		private final String alias;

		private final StringBuilder path = new StringBuilder();

		private SqmPath<?> join;

		public ExpectingEntityJoinDelegate(
				String identifier,
				boolean isTerminal,
				SqmRoot<?> sqmRoot,
				SqmJoinType joinType,
				String alias,
				boolean fetch,
				SqmCreationState creationState) {
			this.creationState = creationState;
			this.sqmRoot = sqmRoot;
			this.joinType = joinType;
			this.fetch = fetch;
			this.alias = alias;

			consumeIdentifier( identifier, isTerminal, true );
		}

		@Override
		public void consumeIdentifier(String identifier, boolean isTerminal, boolean allowReuse) {
			if ( path.length() != 0 ) {
				path.append( '.' );
			}
			path.append( identifier );
			if ( isTerminal ) {
				final String fullPath = path.toString();
				final EntityDomainType<?> joinedEntityType = creationState.getCreationContext()
						.getJpaMetamodel()
						.resolveHqlEntityReference( fullPath );
				if ( joinedEntityType == null ) {
					final SqmCteStatement<?> cteStatement = creationState.findCteStatement( fullPath );
					if ( cteStatement != null ) {
						join = new SqmCteJoin<>( cteStatement, alias, joinType, sqmRoot );
						creationState.getCurrentProcessingState().getPathRegistry().register( join );
						return;
					}
					throw new SemanticException( "Could not resolve join path - " + fullPath );
				}

				assert ! ( joinedEntityType instanceof SqmPolymorphicRootDescriptor );

				if ( fetch ) {
					log.debugf( "Ignoring fetch on entity join : %s(%s)", joinedEntityType.getHibernateEntityName(), alias );
				}

				join = new SqmEntityJoin<>( joinedEntityType, alias, joinType, sqmRoot );
				creationState.getCurrentProcessingState().getPathRegistry().register( join );
			}
		}

		@Override
		public void consumeTreat(String entityName, boolean isTerminal) {
			throw new UnsupportedOperationException();
		}

		@Override
		public SemanticPathPart getConsumedPart() {
			return join;
		}
	}
}
