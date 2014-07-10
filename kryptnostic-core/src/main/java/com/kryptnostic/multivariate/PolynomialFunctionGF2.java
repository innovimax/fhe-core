package com.kryptnostic.multivariate;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cern.colt.bitvector.BitVector;

import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.kryptnostic.multivariate.gf2.Monomial;
import com.kryptnostic.multivariate.gf2.PolynomialFunctionRepresentationGF2;
import com.kryptnostic.multivariate.gf2.SimplePolynomialFunction;

/**
 * This class is used for operating on and evaluating vector polynomial functions over GF(2).
 * Functions are represented an array of monomials along with corresponding BitVector lookup 
 * tables for each monomial's contribution to that output bit. 
 * 
 * The length of each Monomial is the number of input bits to the function.
 * The length of each BitVector in the lookup table is the number of output bits of the function.
 * 
 * 
 * 
 * @author Matthew Tamayo-Rios
 */
public class PolynomialFunctionGF2 extends PolynomialFunctionRepresentationGF2 implements SimplePolynomialFunction {
    private static final int CONCURRENCY_LEVEL = 8;
    private static final Logger logger = LoggerFactory.getLogger( PolynomialFunctionGF2.class );
    private static final ListeningExecutorService executor = MoreExecutors.listeningDecorator( Executors.newFixedThreadPool( 8 ) );
    private static final Predicate<BitVector> notNilContributionPredicate = new Predicate<BitVector>() {
        @Override
        public boolean apply(BitVector v) {
            for( long l : v.elements() ) {
                if( l != 0 ) {
                    return true;
                }
            }
            return false;
        }
    };
    
    public PolynomialFunctionGF2(int inputLength, int outputLength,
            Monomial[] monomials, BitVector[] contributions) {
        super(inputLength, outputLength, monomials, contributions);
     
    }
    
    public static class Builder extends PolynomialFunctionRepresentationGF2.Builder {
        public Builder(int inputLength, int outputLength) {
            super(inputLength, outputLength);
        }
        
        @Override
        protected PolynomialFunctionRepresentationGF2 make(
                int inputLength,
                int outputLength, 
                Monomial[] monomials,
                BitVector[] contributions) {
            return new PolynomialFunctionGF2(inputLength, outputLength, monomials, contributions);
        }
        
        @Override
        public PolynomialFunctionGF2 build() {
            Pair<Monomial[] , BitVector[]> monomialsAndContributions = getMonomialsAndContributions(); 
            return new PolynomialFunctionGF2(inputLength, outputLength, monomialsAndContributions.getLeft() , monomialsAndContributions.getRight() );
        }
    }
    
    public static Builder builder(int inputLength, int outputLength) {
        return new Builder(inputLength, outputLength);
    }
    
    public int getOutputLength() { 
        return outputLength;
    }
    
    public SimplePolynomialFunction xor( SimplePolynomialFunction rhs ) {
        Preconditions.checkArgument( inputLength == rhs.getInputLength() , "Function being added must have the same input length." );
        Preconditions.checkArgument( outputLength == rhs.getOutputLength() , "Function being added must have the same output length." );
        
        Map<Monomial,BitVector> monomialContributionsMap = mapCopyFromMonomialsAndContributions(monomials, contributions);
        Monomial[] rhsMonomials = rhs.getMonomials();
        BitVector[] rhsContributions = rhs.getContributions();
        for( int i = 0 ; i < rhsMonomials.length ; ++i  ) {
            Monomial m = rhsMonomials[ i ];
            BitVector contribution = monomialContributionsMap.get( rhsMonomials[ i ] );
            if( contribution == null ){
                contribution = new BitVector( outputLength ) ;
                monomialContributionsMap.put( m , contribution );
            }
            contribution.xor( rhsContributions[ i ] );
        }
        
        return PolynomialFunctions.fromMonomialContributionMap( inputLength , outputLength , monomialContributionsMap );
    }
    
    public SimplePolynomialFunction and( SimplePolynomialFunction rhs ) {
        Preconditions.checkArgument( inputLength == rhs.getInputLength() , "Functions must have the same input length." );
        Preconditions.checkArgument( outputLength == rhs.getOutputLength() , "Functions must have the same output length." );
        Map<Monomial, BitVector> results = Maps.newHashMap();
        Monomial[] rhsMonomials = rhs.getMonomials();
        BitVector[] rhsContributions = rhs.getContributions();
        for( int i = 0 ; i < monomials.length ; ++i ) {
            for( int j = 0 ;  j < rhsMonomials.length; ++j ) {
                Monomial product = this.monomials[ i ].product( rhsMonomials[ j ] );
                BitVector contribution = this.contributions[ i ].copy();
                contribution.and( rhsContributions[ j ] );
                contribution.xor( Objects.firstNonNull( results.get( product ) , new BitVector( outputLength ) ) );
                results.put( product , contribution );
            }
        }
        
        removeNilContributions( results );
        Monomial[] newMonomials = new Monomial[ results.size() ]; 
        BitVector[] newContributions = new BitVector[ results.size() ];
        int index = 0;
        for( Entry<Monomial ,BitVector> result : results.entrySet() ) {
            BitVector contribution = result.getValue();
            if( contribution.cardinality() > 0 ) {
                newMonomials[ index ] = result.getKey();
                newContributions[ index ] = contribution;
                ++index;
            }
        }
        
        return new PolynomialFunctionGF2( inputLength , outputLength, newMonomials , newContributions);
    }
    
    public BitVector apply( BitVector input ) {
        BitVector result = new BitVector( outputLength );
        
        for( int i = 0 ; i < monomials.length ; ++i ) {
            Monomial term =  monomials[ i ];
            if( term.eval( input ) ) {
                result.xor( contributions[ i ] );
            }
        }
                
        return result;
    }
    
    @Override
    public BitVector apply( BitVector lhs , BitVector rhs ) {
        return apply( FunctionUtils.concatenate( lhs , rhs) );
    }
    
    @Override
    public SimplePolynomialFunction compose( SimplePolynomialFunction inner ) {
        //Verify the functions are composable
        Preconditions.checkArgument( 
                inputLength == inner.getOutputLength() ,
                "Input length of outer function must match output length of inner function it is being composed with"
                );
        Set<Monomial> requiredMonomials = Monomials.deepCloneToMutableSet( monomials );
        Set<Monomial> stoppingMonomials = Monomials.deepCloneToImmutableSet( monomials );
        Map<Monomial, Set<Monomial>> memoizedComputations = initializeMemoMap( inputLength , inner.getMonomials() , inner.getContributions() );
        requiredMonomials.remove( Monomial.constantMonomial( inputLength ) );
        /*
         * Figure out most desirable product to compute next and use this to build up all required monomials.
         * 
         * We do this by:
         * 
         * 1) Computing all pairwise computable monomial factors for the outer monomial, 
         * which is cheap relative to an actual product computation.
         * 
         * 2) Finding the most frequently occurring factors from step 1.
         * 
         * 3) Computing the product corresponding to most frequently occurring factor.
         * 
         * 4) Memoizing the product from step 3.
         * 
         * We are done when we've have computed the products for all outer monomials.
         */
        int maxMonomialOrder = getMaximumMonomialOrder();
        Map<Monomial, List<Monomial>> possibleProducts = allPossibleProductParallelEx2( memoizedComputations.keySet(), memoizedComputations.keySet(), requiredMonomials , maxMonomialOrder );  // 0
        while( !Sets.difference( requiredMonomials , memoizedComputations.keySet() ).isEmpty() ) {
            //TODO: allPossibleProductts already filters out previously computed products, remove double filtering.
            //TODO: Don't compute products that don't exist. Do this by only computing products which will be used.
            Monomial mostFrequent = mostFrequentFactorParallel( possibleProducts.keySet() , requiredMonomials );                
            List<Monomial> factors = Preconditions.checkNotNull( 
                    possibleProducts.get( mostFrequent ) ,
                    "Composition failure! Encountered unexpected null when searching for next product to compute.");
            Set<Monomial> mproducts = product( 
                    Preconditions.checkNotNull( memoizedComputations.get( factors.get( 0 ) ) ), 
                    Preconditions.checkNotNull( memoizedComputations.get( factors.get( 1 ) ) ) );  //3
            memoizedComputations.put( mostFrequent , mproducts ); //4
            possibleProducts.remove(  mostFrequent );
            possibleProducts.putAll( allPossibleProductParallelEx2( ImmutableSet.of(mostFrequent) , memoizedComputations.keySet(), requiredMonomials , maxMonomialOrder ) );  
        }
        
        Set<Monomial> remainders = Sets.difference( stoppingMonomials , memoizedComputations.keySet() ); 
        
        for( Monomial remainder : remainders ) {
            for( Monomial required : requiredMonomials ) {
                Optional<Monomial> divResult = remainder.divide( required );
                if( divResult.isPresent() && memoizedComputations.containsKey( divResult.get() ) ) {
                    Set<Monomial> mproducts = product( 
                            Preconditions.checkNotNull( memoizedComputations.get( divResult.get() ) ), 
                            Preconditions.checkNotNull( memoizedComputations.get( required ) ) );  
                    memoizedComputations.put( remainder , mproducts ); 
                }
            }
        }
        
        Map<Monomial, BitVector> composedFunction = Maps.newHashMap();
        
        /*
         * Each monomial that has been computed in terms of the inner function contributes a set of monomials
         * to each output of the outer function.  We need to resolve the sum of all of these in order to calculate
         * what the contribution of each newMonomial looks like.
         * 
         * For each BitVector in the contribution we check if that monomial contributes.
         */
        
        for( int row = 0; row < outputLength ; ++row ) {
            Set<Monomial> monomialsForOutputRow = ImmutableSet.of();
            for( int i = 0 ; i < contributions.length; ++i ) {
                if( contributions[ i ].get( row ) ) {
                    //Symmetric difference, is equivalently to repeatedly xoring the sets together
                    monomialsForOutputRow = Sets.symmetricDifference( 
                            monomialsForOutputRow , 
                            Preconditions.checkNotNull(
                                    memoizedComputations.get( monomials[ i ] ) ,
                                    "Monomial contributions cannot be null for a required monomial"
                                    ) 
                            );
                }
            }
            
            //For each monomial contributing to the output, set the contribution bit in the new contribution vectors.
            for( Monomial monomial : monomialsForOutputRow ){
                BitVector contribution = composedFunction.get( monomial );
                if( contribution == null ) {
                    contribution = new BitVector( outputLength );
                    composedFunction.put( monomial , contribution );
                }
                contribution.set( row );
            }
            
        }
        
        return PolynomialFunctions.fromMonomialContributionMap( inner.getInputLength() , outputLength , composedFunction );  
    }
    
    @Override
    public SimplePolynomialFunction compose( SimplePolynomialFunction lhs, SimplePolynomialFunction rhs) {
        return this.compose( PolynomialFunctions.concatenate( lhs , rhs ) );
        
    }
    
    public PolynomialFunctionGF2 extend( int length ) {
        //TODO: Add re-entrant read/write lock for updating contributions.
        Monomial[] newMonomials = new Monomial[ monomials.length ];
        BitVector[] newContributions = new BitVector[ monomials.length ];
        
        for( int i = 0 ; i < contributions.length ; ++i ) {
            BitVector current = contributions[ i ];
            newMonomials[ i ] = monomials[ i ].clone();
            newContributions[ i ] = new BitVector( Arrays.copyOf( current.elements() , current.elements().length << 1 ) , current.size() << 1 );
        }
        
        return new PolynomialFunctionGF2(length, length, newMonomials, newContributions);
    }
    
    public PolynomialFunctionGF2 clone() {
        Monomial[] newMonomials = new Monomial[ monomials.length ];
        BitVector[] newContributions = new BitVector[ monomials.length ];
        
        for( int i = 0 ; i < monomials.length ; ++i ) {
            newMonomials[i] = monomials[i].clone();
            newContributions[i] = contributions[i].copy();
        }
        
        return new PolynomialFunctionGF2( 
                inputLength, 
                outputLength, 
                newMonomials, 
                newContributions );
    }
    
    @Override
    public int getTotalMonomialCount() {
        int count = 0;
        for( int i = 0 ; i < monomials.length ; ++i ) {
            count += contributions[ i ].cardinality();
        }
        return count;
    }
    
    @Override
    public int getMaximumMonomialOrder() {
        int maxOrder = 0;
        for( Monomial m : monomials ) {
            maxOrder = Math.max( maxOrder , m.cardinality() );
        }
        return maxOrder;
    }
    
    public static Map<Monomial, Set<Monomial>> initializeMemoMap( int outerInputLength , Monomial[] monomials , BitVector[] contributions ) {
        Map<Monomial, Set<Monomial>> memoizedComputations = Maps.newHashMap();
        for( int i = 0 ; i < outerInputLength ; ++i ) {
            memoizedComputations.put( 
                    Monomial.linearMonomial( outerInputLength , i ) , 
                    contributionsToMonomials( i , monomials , contributions )
                    );
        }
        
        return memoizedComputations;
    }
      
    public static Monomial mostFrequentFactor( Monomial[] toBeComputed , Set<Monomial> readyToCompute , Set<Monomial> alreadyComputed ) {
        Monomial result = null;
        int max = -1;
        for( Monomial ready : readyToCompute ) {
            if( !alreadyComputed.contains( ready ) ) {
                int count = 0;
                for( Monomial onDeck : toBeComputed ) {
                    if( onDeck.hasFactor( ready ) ) {
                        count++;
                    }
                }
                if( count > max ) {
                    max = count;
                    result = ready;
                }
            }
        }
        return result;
    }
    
    public static Set<Monomial> getCandidatesForProducting( final Set<Monomial> monomials , final Set<Monomial> requiredMonomials ) {
        /*
         * Only consider products that can be formed from existing monomials and will divide something that can be computed. 
         */
        final Set<Monomial> candidates = Sets.newConcurrentHashSet();
        final CountDownLatch latch = new CountDownLatch( requiredMonomials.size() );
        for( final Monomial required : requiredMonomials ) {
            executor.execute( new Runnable() {
                @Override
                public void run() {
                    for( Monomial m : monomials ) {
                        Optional<Monomial> result = required.divide( m );
                        if( result.isPresent() ) {
                            candidates.add( result.get() );
                        }
                    }
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            logger.error("Thread interrupted while waiting on candidates for producting.");
        }
        
        return candidates;
    }
    //TODO: Decide whether its worth unit testing this.
    public static Map<Monomial,List<Monomial>> allPossibleProduct( final Set<Monomial> monomials ) {
        Map<Monomial, List<Monomial>> result = Maps.newHashMapWithExpectedSize( ( monomials.size() * ( monomials.size()  - 1 ) ) >>> 1 );

        for( final Monomial lhs : monomials ) {
            for( Monomial rhs : monomials ) {
                //Skip identical monomials
                if( !lhs.equals( rhs ) ) {
                    Monomial product = lhs.product( rhs );
                    //Don't bother adding it to the list of possible products, if we've already seen it before.
                    if( !monomials.contains( product ) ) {
                        result.put( product , ImmutableList.of( lhs, rhs ) );
                    }
                }
            }
        }
        

        return result;
    }
    
    //TODO: Decide whether its worth unit testing this.
    public static Map<Monomial,List<Monomial>> allPossibleProductParallel( final Set<Monomial> monomials ) {
        final ConcurrentMap<Monomial, List<Monomial>> result = 
                new MapMaker()
                    .concurrencyLevel( CONCURRENCY_LEVEL )
                    .initialCapacity( ( monomials.size() * ( monomials.size()  - 1 ) ) >>> 1 )
                    .makeMap();
        
        final CountDownLatch latch = new CountDownLatch( CONCURRENCY_LEVEL );
        final Monomial[] monomialsForThreads = monomials.toArray( new Monomial[0] );
        int increment = monomialsForThreads.length / CONCURRENCY_LEVEL;
        for( int i = 0; i < CONCURRENCY_LEVEL;  ) {
            final int start = i;
            final int stop = Math.min( (++i) * CONCURRENCY_LEVEL, monomialsForThreads.length );
            executor.execute( new Runnable() {
                @Override
                public void run() {
                    for( int i = start ; i < stop ; ++i ) {
                        Monomial lhs = monomialsForThreads[ i ];
                        for( Monomial rhs : monomials ) {
                            //Skip identical monomials
                            if( !lhs.equals( rhs ) ) {
                                Monomial product = lhs.product( rhs );
                                //Don't bother adding it to the list of possible products, if we've already seen it before.
                                if( !monomials.contains( product ) ) {
                                    result.putIfAbsent( product , ImmutableList.of( lhs, rhs ) );
                                }
                            }
                        }
                    }
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            logger.error("Thread interrupted while waiting on all possible products.");
        }
        return result;
    }
    
    //TODO: Decide whether its worth unit testing this.
    public static Map<Monomial,List<Monomial>> allPossibleProductParallelEx( final Set<Monomial> monomials ) {
        final ConcurrentMap<Monomial, List<Monomial>> result = 
                new MapMaker()
                    .concurrencyLevel( CONCURRENCY_LEVEL )
                    .initialCapacity( ( monomials.size() * ( monomials.size()  - 1 ) ) >>> 1 )
                    .makeMap();
        final Monomial[] monomialArray = monomials.toArray( new Monomial[0] );
        final CountDownLatch latch = new CountDownLatch( monomials.size() );
        for( int i = 0 ; i <  monomialArray.length ; ++i ) {
            final Monomial lhs = monomialArray[ i ];
            final int currentIndex = i;
            executor.execute( new Runnable() {
                @Override
                public void run() { 
                    for( int j = currentIndex ; j < monomialArray.length ; ++j ) {
                        Monomial rhs = monomialArray[ j ];
                        //Skip identical monomials
                        if( !lhs.equals( rhs ) ) {
                            Monomial product = lhs.product( rhs );
                            //The only way we see an already existing product is x1 x2 * x2 * x3
                            if( !monomials.contains( product ) ) {
                                result.putIfAbsent( product , ImmutableList.of( lhs, rhs ) );
                            }
                        }
                    }
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            logger.error("Thread interrupted while waiting on all possible products.");
        }
        return result;
    }
    
    //TODO: Decide whether its worth unit testing this.
    public static Map<Monomial,List<Monomial>> allPossibleProductParallelEx2( final Set<Monomial> newMonomials, final Set<Monomial> monomials, final Set<Monomial> remainingMonomials , final int maxMonomialOrder ) {
        final ConcurrentMap<Monomial, List<Monomial>> result = 
                new MapMaker()
                    .concurrencyLevel( CONCURRENCY_LEVEL )
                    .initialCapacity( ( monomials.size() * ( monomials.size()  - 1 ) ) >>> 1 )
                    .makeMap();
        final CountDownLatch latch = new CountDownLatch( newMonomials.size() );
        for( final Monomial lhs : newMonomials ) {
            executor.execute( 
                    new Runnable() {
                        @Override
                        public void run() {
                            for( Monomial rhs : monomials ) {
                                //New monmials are guaranteed to be processed by their corresponding thread.
                                if( lhs!=rhs && (newMonomials == monomials || !newMonomials.contains( rhs ) ) ) {
                                    Monomial product = lhs.product( rhs );
                                    if( product.cardinality() <= maxMonomialOrder ) {
                                        for( Monomial remainingMonomial : remainingMonomials ) {
                                            if( remainingMonomial.hasFactor( product ) ) {
                                                //TODO: Determine if multiple ways to achieve same product are worth tracking.
                                                result.putIfAbsent( product , ImmutableList.of( lhs, rhs ) );
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            latch.countDown();
                        }
                    });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return result;
    }
    
    
    public static Monomial mostFrequentFactorParallel( final Set<Monomial> monomials, final Set<Monomial> remainingMonomials ) {
        final class MostFrequentFactorResult {
            int count = 0;
            Monomial mostFrequentMonomial = null;
        }; 
        final MostFrequentFactorResult result = new MostFrequentFactorResult();
        final Lock updateLock = new ReentrantLock();
        final CountDownLatch latch = new CountDownLatch( monomials.size() );
        for( final Monomial m : monomials ) {
            executor.execute( new Runnable() {
                @Override
                public void run() {
                    int count = 0;
                    for( Monomial remainingMonomial : remainingMonomials ) {
                        if( remainingMonomial.hasFactor( m ) ) {
                            ++count;  
                        }
                    }
                    
                    try {
                        updateLock.lock();
                        if( count > result.count ) {
                            result.count = count;
                            result.mostFrequentMonomial = m;
                        }
                    } finally {
                        updateLock.unlock();
                    }
                    
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
        Set<Monomial> sharesFactor = Sets.newHashSet();
        
        for( Monomial rM : remainingMonomials ) {
            if( rM.hasFactor( result.mostFrequentMonomial ) ) {
                sharesFactor.add( rM );
            }
        }
        
        for( Monomial sF : sharesFactor ) {
            remainingMonomials.remove( sF );
            sF.xor( result.mostFrequentMonomial );
            if( !sF.isZero() ) {
                remainingMonomials.add( sF );
            }
        }
        
        return result.mostFrequentMonomial;
    }
   
    //TODO: Figure out whether this worth unit testing.
    public static Set<Monomial> product( Set<Monomial> lhs, Set<Monomial> rhs ) {
        Set<Monomial> result = Sets.newHashSetWithExpectedSize( lhs.size() * rhs.size() / 2 );
        for( Monomial mlhs : lhs ) {
            for( Monomial mrhs : rhs ) {
                Monomial product = mlhs.product( mrhs );
                if( !result.add( product ) ) {
                    result.remove( product );
                }
            }
        }
        return result;
    }
    
    public static Map<Monomial, BitVector> mapCopyFromMonomialsAndContributions( Monomial[] monomials, BitVector[] contributions ) {
        Map<Monomial, BitVector> result = Maps.newHashMapWithExpectedSize( monomials.length );
        for( int i = 0 ; i < monomials.length ; ++i  ) {
            result.put( monomials[ i ].clone() , contributions[ i ].copy() );
        }
        return result;
    }
    
    
    public static void removeNilContributions( Map<Monomial,BitVector> monomialContributionMap ) {
        Set<Monomial> forRemoval = Sets.newHashSet();
        for( Entry<Monomial,BitVector> monomialContribution : monomialContributionMap.entrySet() ) {
            if( !notNilContributionPredicate.apply( monomialContribution.getValue() ) ) {
                forRemoval.add( monomialContribution.getKey() );
            }
        }
        for( Monomial m : forRemoval ) {
            monomialContributionMap.remove( m );
        }
    }
    
    //TODO: Figure out what's more efficient filter keys + copy to immutable map, or removing from existing map.
    public static Map<Monomial,BitVector> filterNilContributions( Map<Monomial, BitVector> monomialContributionMap ) {
        return ImmutableMap.copyOf( Maps.filterKeys( monomialContributionMap , notNilContributionPredicate ) );
    }
    
    public static Set<Monomial> contributionsToMonomials( int row , Monomial[] monomials, BitVector[] contributions ) {
        /*
         * Converts a single row of contributions into monomials.
         */
        Set<Monomial> result =Sets.newHashSetWithExpectedSize( contributions.length/2 );
        for( int i = 0 ; i < contributions.length ; ++i ) {
            if( contributions[ i ].get( row ) ) {
                result.add( monomials[ i ] );
            }
        }
        return result;
    }
    
    public static PolynomialFunctionGF2 truncatedIdentity( int outputLength , int inputLength ) {
        return truncatedIdentity( 0 , outputLength - 1 , inputLength );
    }
    
    public static PolynomialFunctionGF2 truncatedIdentity( int startMonomial , int stopMonomial , int inputLength) {
        int outputLength = stopMonomial - startMonomial + 1;
        Monomial[] monomials = new Monomial[ outputLength ];
        BitVector[] contributions = new BitVector[ outputLength ];
        
        for( int i = 0 ; i < outputLength ; ++i ) {
            monomials[i] = Monomial.linearMonomial( inputLength , i );
            BitVector contribution = new BitVector( outputLength );
            contribution.set( i );
            contributions[i] = contribution;
        }
        
        return new PolynomialFunctionGF2( inputLength , outputLength , monomials , contributions);
    }
    
    public static PolynomialFunctionGF2 prepareForLhsOfBinaryOp( PolynomialFunctionGF2 lhs ) {
        Monomial[] monomials = new Monomial[ lhs.monomials.length ];
        BitVector[] contributions = new BitVector[ lhs.contributions.length ];
        for( int i = 0 ; i < lhs.monomials.length ; ++i ) {
            long[] elements = monomials[i].elements();
            monomials[i] = new Monomial( Arrays.copyOf( elements , elements.length << 1 ), lhs.getInputLength() << 1 );
            contributions[i] = contributions[i].copy();
        }
        
        return new PolynomialFunctionGF2( monomials[0].size() , contributions.length , monomials, contributions );
    }
    
    public static PolynomialFunctionGF2 prepareForRhsOfBinaryOp( PolynomialFunctionGF2 rhs ) {
        Monomial[] monomials = new Monomial[ rhs.monomials.length ];
        BitVector[] contributions = new BitVector[ rhs.contributions.length ];
        for( int i = 0 ; i < rhs.monomials.length ; ++i ) {
            long[] elements = monomials[i].elements();
            long[] newElements = new long[ elements.length << 1 ];
            for( int j = 0 ; j < elements.length ; ++j ) {
                newElements[ j ] = elements[ j ];
            }
            monomials[i] = new Monomial( newElements , rhs.getInputLength() << 1 );
            contributions[i] = contributions[i].copy();
        }
        
        return new PolynomialFunctionGF2( monomials[0].size() , contributions.length , monomials, contributions );
    }
    
   
}
