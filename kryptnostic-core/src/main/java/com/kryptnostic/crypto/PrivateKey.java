package com.kryptnostic.crypto;

import java.nio.ByteBuffer;
import java.security.InvalidParameterException;
import java.util.Arrays;

import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cern.colt.bitvector.BitVector;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.kryptnostic.linear.EnhancedBitMatrix;
import com.kryptnostic.linear.EnhancedBitMatrix.NonSquareMatrixException;
import com.kryptnostic.linear.EnhancedBitMatrix.SingularMatrixException;
import com.kryptnostic.multivariate.FunctionUtils;
import com.kryptnostic.multivariate.PolynomialFunctions;
import com.kryptnostic.multivariate.gf2.SimplePolynomialFunction;
import com.kryptnostic.multivariate.parameterization.ParameterizedPolynomialFunctions;

/**
 * Private key class for decrypting data.
 * @author Matthew Tamayo-Rios
 */
public class PrivateKey {
	private static final int DEFAULT_CHAIN_LENGTH = 2; //The value of this constant is fairly arbitrary.
    private static final Logger logger = LoggerFactory.getLogger( PrivateKey.class );
//    private static ObjectMapper mapper = new ObjectMapper();
    private final EnhancedBitMatrix D;
    private final EnhancedBitMatrix L;
    private final EnhancedBitMatrix E1;
    private final EnhancedBitMatrix E2;
    
    private final EnhancedBitMatrix A;
    private final EnhancedBitMatrix B;
    
    private final SimplePolynomialFunction F;
    private final SimplePolynomialFunction G;
    private final SimplePolynomialFunction decryptor;
    private final SimplePolynomialFunction[] complexityChain;
    private final Function<SimplePolynomialFunction, SimplePolynomialFunction> composer;
    private final int longsPerBlock;
    
    public PrivateKey( int cipherTextBlockLength, int plainTextBlockLength) {
    	this( cipherTextBlockLength, plainTextBlockLength, DEFAULT_CHAIN_LENGTH );
    }
    /**
     * Construct a private key instance that can be used for decrypting data encrypted with the public key.
     * @param cipherTextBlockLength Length of the ciphertext output block, should be multiples of 64 bits. 
     * @param plainTextBlockLength Length of the ciphertext output block, should be multiples of 64 bits.
     * @param complexityChainLength Number of multivariate quadratic equations in the complexity chain.
     */
    public PrivateKey( int cipherTextBlockLength , int plainTextBlockLength , int complexityChainLength ) {
        Preconditions.checkArgument( 
                cipherTextBlockLength > plainTextBlockLength , 
                "Ciphertext block length must be greater than plaintext block length." );
        boolean initialized = false;
        int rounds = 100000;
        EnhancedBitMatrix e2gen = null ,dgen = null , e1gen = null,lgen = null;
        while( !initialized && ( (--rounds)!=0 ) ) {
            
            /*
             * Loop until valid matrices have been generated.
             */
            try {
                e1gen = EnhancedBitMatrix.randomMatrix( cipherTextBlockLength , plainTextBlockLength );
                dgen = e1gen.getLeftNullifyingMatrix();
                e2gen = dgen.rightGeneralizedInverse();
                lgen = e2gen.getLeftNullifyingMatrix();
                lgen = lgen.multiply( e1gen ).inverse().multiply( lgen );  //Normalize
                
                Preconditions.checkState( dgen.multiply( e1gen ).isZero() , "Generated D matrix must nullify E1." );
                Preconditions.checkState( lgen.multiply( e2gen ).isZero() , "Generated L matrix must nullify E2." );
                Preconditions.checkState( dgen.multiply( e2gen ).isIdentity(), "Generated D matrix must be left generalized inverse of E2." );
                Preconditions.checkState( lgen.multiply( e1gen ).isIdentity(), "Generated D matrix must be left generalized inverse of E2." );
                
                initialized = true;
                
                logger.info("E1GEN: {} x {}" , e1gen.rows(), e1gen.cols() );
                logger.info("E2GEN: {} x {}" , e2gen.rows(), e2gen.cols() );
                logger.info("DGEN: {} x {}" , dgen.rows(), dgen.cols() );
//                logger.info("LGEN: {} x {}" , lgen.rows(), lgen.cols() );
            } catch (SingularMatrixException e1) {
                continue;
            }
        }
        
        Preconditions.checkState( initialized, "Unable to generate private key. Make sure cipherTextBlockLength > plainTextBlockLength " );
        
        D = dgen;
        L = lgen;
        E1 = e1gen;
        E2 = e2gen;
        
        EnhancedBitMatrix Agen;
        EnhancedBitMatrix Bgen;
        
        try {
            do {
                Agen = EnhancedBitMatrix.randomInvertibleMatrix( plainTextBlockLength );
                Bgen = EnhancedBitMatrix.randomInvertibleMatrix( plainTextBlockLength );
            } while ( !EnhancedBitMatrix.determinant( Agen.add(Bgen) ) );
        } catch (NonSquareMatrixException e) {
            //This should never happen.
            throw new Error("Encountered non-square matrix, where non-should exist.");
        } 
        
        A = Agen;
        B = Bgen;
        complexityChain = PolynomialFunctions.arrayOfRandomMultivariateQuadratics( plainTextBlockLength , plainTextBlockLength , DEFAULT_CHAIN_LENGTH );
        F = PolynomialFunctions.randomFunction( plainTextBlockLength , plainTextBlockLength , 10 , 3 );
        G = PolynomialFunctions.randomManyToOneLinearCombination(plainTextBlockLength);
        
        
        composer = PolynomialFunctions.getComposer(G);

        try {
            decryptor = buildDecryptor();
        } catch (SingularMatrixException e) {
            logger.error("Unable to generate decryptor function due to a singular matrix exception during generation process.");
            throw new InvalidParameterException("Unable to generate decryptor function for private key.");
        }
        longsPerBlock = cipherTextBlockLength >>> 6;
    }

    public SimplePolynomialFunction encryptBinary( SimplePolynomialFunction plaintextFunction ) {
        int plaintextLen =  E1.cols();
        SimplePolynomialFunction R = PolynomialFunctions.randomFunction( plaintextFunction.getInputLength() , plaintextLen );
        SimplePolynomialFunction lhsR = F.compose( R );
        
        return E1
                .multiply( plaintextFunction.xor( lhsR ) )
                .xor( E2.multiply( R ) );
    }
    
    SimplePolynomialFunction encrypt( SimplePolynomialFunction input ) {
        return encrypt( input , G );
    }
    
    public SimplePolynomialFunction encrypt( SimplePolynomialFunction input , SimplePolynomialFunction g ) {
        Pair<SimplePolynomialFunction,SimplePolynomialFunction[]> pipeline = PolynomialFunctions.buildNonlinearPipeline( g , complexityChain );
        
        SimplePolynomialFunction E = 
                E1.multiply( input.xor( A.multiply( g ) ) ).xor( E2.multiply( input.xor( B.multiply( g ) ) ) );
        return E.xor( ParameterizedPolynomialFunctions.fromUnshiftedVariables( g.getInputLength() , E1.multiply( pipeline.getLeft() ).xor( E2.multiply( pipeline.getLeft() ) ) , pipeline.getRight() ) );
    }
    
    public SimplePolynomialFunction computeHomomorphicFunction( SimplePolynomialFunction f ) {
        return encrypt( f.compose( decryptor ) , PolynomialFunctions.randomManyToOneLinearCombination( E1.cols() ) );
    }
    
    public SimplePolynomialFunction computeBinaryHomomorphicFunction( SimplePolynomialFunction f ) {
        return encryptBinary( f.compose( FunctionUtils.concatenateInputsAndOutputs( decryptor, decryptor ) ) );
    }
    
    public EnhancedBitMatrix getD() {
        return D;
    }

    public EnhancedBitMatrix getL() {
        return L;
    }

    public EnhancedBitMatrix getE1() {
        return E1;
    }

    public EnhancedBitMatrix getE2() {
        return E2;
    }

    public EnhancedBitMatrix getA() {
        return A;
    }
    
    public EnhancedBitMatrix getB() {
        return B;
    }
    
    public SimplePolynomialFunction getG() {
        return G;
    }
    
    public EnhancedBitMatrix randomizedL() throws SingularMatrixException {
        EnhancedBitMatrix randomL = Preconditions.checkNotNull( E2 , "E2 must not be null." ).getLeftNullifyingMatrix();
        return  randomL.multiply( Preconditions.checkNotNull( E1 , "E1 must not be null.") ).inverse().multiply( randomL );  //Normalize
    }
    
    byte[] decrypt( byte[] ciphertext ) {
        ByteBuffer buffer = ByteBuffer.wrap( ciphertext );
        ByteBuffer decryptedBytes = ByteBuffer.allocate( ciphertext.length >>> 1);
        while( buffer.hasRemaining() ) {
            BitVector X  = fromBuffer( buffer , longsPerBlock );
            BitVector plaintextVector = decryptor.apply( X );
            toBuffer( decryptedBytes , plaintextVector );
        }
        return decryptedBytes.array();
    }
    
    public SimplePolynomialFunction getDecryptor() {
        return decryptor;
    }
    
    public SimplePolynomialFunction buildDecryptor() throws SingularMatrixException {
        /*
         * G( x ) = Inv( A + B ) (L + D) x 
         * D( x ) = L x + A G( x ) + c'_1 h'_1 + c'_2 h'_2 
         */
        SimplePolynomialFunction X = PolynomialFunctions.identity( E1.rows() );
        SimplePolynomialFunction GofX = A.add( B ).inverse().multiply( L.add( D ) ).multiply( X );
        
        Pair<SimplePolynomialFunction,SimplePolynomialFunction[]> pipeline = PolynomialFunctions.buildNonlinearPipeline( GofX , complexityChain );
        SimplePolynomialFunction DofX = L.multiply( X ).xor( A.multiply( GofX ) ).xor( ParameterizedPolynomialFunctions.fromUnshiftedVariables( GofX.getInputLength() , pipeline.getLeft() , pipeline.getRight() ) );
        return DofX;
    }
    
    public byte[] decryptFromEnvelope(Ciphertext ciphertext) {
        /*
         * Decrypt using the message length to discard unneeded bytes.
         */
        return Arrays.copyOf( 
                decrypt( ciphertext.getContents() ) , 
                (int) decryptor.apply( new BitVector( ciphertext.getLength() , longsPerBlock << 6 ) ).elements()[0] );
    }
    
    protected static void toBuffer( ByteBuffer output , BitVector plaintextVector ) {
        long[] plaintextLongs = plaintextVector.elements();
        for( long l : plaintextLongs ) {
            output.putLong( l );
        }
    }
    
    protected static BitVector fromBuffer( ByteBuffer buffer , int longsPerBlock ) {
        long [] cipherLongs = new long[ longsPerBlock ];
        for( int i = 0 ; i < longsPerBlock ; ++i ) {
            cipherLongs[i] = buffer.getLong();
            logger.debug("Read the following ciphertext: {}", cipherLongs[i]);
        }
        
        return new BitVector( cipherLongs , longsPerBlock << 6 );
    }
//    public abstract Object decryptObject( Object object ,  Class<?> clazz );
}