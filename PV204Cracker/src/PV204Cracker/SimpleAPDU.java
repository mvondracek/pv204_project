/*
This file was merged and modified based on "santomet/pv204_project"
which was published under WTFPL license.
https://github.com/santomet/pv204_project/blob/231c6c6ed962f63b1d027d0746994e58c412886c/LICENSE
*/

package PV204Cracker;

import org.bouncycastle.math.ec.ECCurve;
import org.bouncycastle.math.ec.ECPoint;
import applets.AlmostSecureApplet;
import cardTools.CardManager;
import cardTools.RunConfig;
import cardTools.Util;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import javacard.framework.APDU;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.OwnerPIN;
import javacard.security.AESKey;
import javacard.security.Checksum;
import javacard.security.Key;
import javacard.security.KeyBuilder;
import javacard.security.KeyPair;
import javacard.security.RandomData;
import javacard.security.Signature;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import javax.smartcardio.CommandAPDU;
import javax.smartcardio.ResponseAPDU;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.BigIntegers;


/**
 * Test class.
 * Note: If simulator cannot be started try adding "-noverify" JVM parameter
 *
 * @author Petr Svenda (petrs), Dusan Klinec (ph4r05)
 */
public class SimpleAPDU {
    private static String APPLET_AID = "482871d58ab7465e5e05";
    private static byte APPLET_AID_BYTE[] = Util.hexStringToByteArray(APPLET_AID);

    private static final String STR_APDU_GETRANDOM = "B054100000";
    
    private static final Integer BIGINT_LENGTH = 32; 
    private static final Integer POINT_LENGTH = 33;
    private static final Integer JPAKE1_TOTAL_LENGTH = 196; 
    private static final Integer JPAKE2_TOTAL_LENGTH = 196; 
    private static final Integer JPAKE3_TOTAL_LENGTH = 98;
    private static final Integer JPAKE4_TOTAL_LENGTH = 98;
    private static final Integer MESSAGE_HEADER_LENGTH = 4;
    private static final Integer MESSAGE_LENGTH_OFFSET = 3;
    private static final boolean COMPRESS_POINTS = true;
    private static final Integer ZKP_LENGTH = BIGINT_LENGTH + POINT_LENGTH;
    
    final static byte INS_ENCRYPT = (byte) 0x50;
    final static byte INS_DECRYPT = (byte) 0x51;
    final static byte INS_SETKEY = (byte) 0x52;
    final static byte INS_HASH = (byte) 0x53;
    final static byte INS_RANDOM = (byte) 0x54;
    final static byte INS_VERIFYPIN = (byte) 0x55;
    final static byte INS_SETPIN = (byte) 0x56;
    final static byte INS_RETURNDATA = (byte) 0x57;
    final static byte INS_SIGNDATA = (byte) 0x58;
    
    final static short SESSION_COUNTER_OFFSET = (short) 0x0;
    final static short SESSION_CHECKSUM_OFFSET = (short) 0x01;
    final static short SESSION_DATALENGTH_OFFSET = (short) 0x03;
    final static short SESSION_DATA_OFFSET = (short) 0x04;
    
    private static final short RESPONSE_OK = (short) 0x9000;
    
    private static byte[] CARD_ID   = null;
    private static byte[] PC_ID     = null;
    private static byte[] PIN       = null;
    private static BigInteger SHARED_BIG_INT = null;
    
    private short counter = (byte) 0x00;
    
    private ECParameterSpec ecSpec  = null;
    private ECCurve.Fp  ecCurve     = null;
    private BigInteger n        = null;
    private ECPoint G           = null;
    private ECPoint pointG1     = null;
    private ECPoint pointG2     = null;
    private ECPoint pointG3     = null;
    private ECPoint pointG4     = null;
    private ECPoint GA          = null;
    private ECPoint A           = null;
    
    private BigInteger x1 = null;
    private BigInteger x2 = null;
    
    private ECPoint pointK = null;
    private byte [] key = null;
    
    
    private SecretKey m_aesKey     = null;
    private Cipher m_encryptCipher = null;
    private Cipher m_decryptCipher = null;
    private RandomData m_secureRandom = null;
    protected MessageDigest m_hash = null;
    private Checksum m_checksum = null;
        
    
    protected SimpleAPDU() {
        CARD_ID = new byte[] {'c', 'a', 'r', 'd'};
        PC_ID = new byte[] {'u', 's', 'e', 'r'};
        PIN = new byte[] {0x01, 0x02, 0x03, 0x04};
        SHARED_BIG_INT = BigIntegers.fromUnsignedByteArray(PIN);
        ecSpec = ECNamedCurveTable.getParameterSpec("prime256v1");
        ecCurve = (ECCurve.Fp) ecSpec.getCurve();
        G = ecSpec.getG();
        n = ecSpec.getN();
        m_checksum = Checksum.getInstance(Checksum.ALG_ISO3309_CRC16, false);
       
        try {
            // CREATE OBJECTS FOR CBC CIPHERING
            m_encryptCipher = Cipher.getInstance("AES/CBC/NoPadding");
            m_decryptCipher = Cipher.getInstance("AES/CBC/NoPadding");
        } catch (NoSuchAlgorithmException ex) {
            Logger.getLogger(SimpleAPDU.class.getName()).log(Level.SEVERE, null, ex);
        } catch (NoSuchPaddingException ex) {
            Logger.getLogger(SimpleAPDU.class.getName()).log(Level.SEVERE, null, ex);
        }


        // CREATE RANDOM DATA GENERATORS
        m_secureRandom = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
        int v = bytes[j] & 0xFF;
        hexChars[j * 2] = HEX_ARRAY[v >>> 4];
        hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
    }
    return new String(hexChars);
}
    
        
    private boolean CreateSecureChannel(CardManager cardMngr, byte[] pin)  throws Exception {
        BigInteger sharedBigInt = BigIntegers.fromUnsignedByteArray(pin);
        byte[] APDUdata = JPAKE1();
        if (APDUdata.length != JPAKE1_TOTAL_LENGTH) {
            // Generated APDU data has different length than they should have
            return false;
        }
        
        // Send to card ang get the response
        final ResponseAPDU response2 = cardMngr.transmit(new CommandAPDU(0xB0, 0x01, 0x00, 0x00, APDUdata, JPAKE2_TOTAL_LENGTH)); // Use other constructor for CommandAPDU
        byte[] responseData2 = response2.getData();
        if ((short) response2.getSW() != RESPONSE_OK || responseData2.length != JPAKE2_TOTAL_LENGTH) {
            // Processing of APDU on card was not successful or the response has bad length
            return false;
        }
        
        if (!JPAKE2(responseData2)){
            // ZKP for x3 (or x4) was not correct
            System.out.println(" JPAKE2 fail.");
            return false;
        }
        
        byte[] APDUdata3 = JPAKE3(sharedBigInt);
        if (APDUdata3.length != JPAKE3_TOTAL_LENGTH) {
            // Generated APDU data has different length than they should have
            return false;
        }
        
        final ResponseAPDU response4 = cardMngr.transmit(new CommandAPDU(0xB0, 0x02, 0x00, 0x00, APDUdata3, JPAKE4_TOTAL_LENGTH)); // Use other constructor for CommandAPDU
        byte[] responseData4 = response4.getData();
        if ((short) response4.getSW() != RESPONSE_OK || responseData4.length != JPAKE4_TOTAL_LENGTH) {
            // Processing of APDU on card was not successful or the response has bad length
            return false;
        }
        
        if (!JPAKE4(responseData4, sharedBigInt)){
            // ZKP for x4 * s was not correct
            System.out.println(" JPAKE4 fail.");
            return false;
        }
        
        return true;
    }
    

    static CommandAPDU deselectAPDU() {
        /* Creates deselect APDU */
        return new CommandAPDU(0xB0, 0x03, 0x00, 0x00);
    }

    private byte[] JPAKE1() throws IOException {
        /* get random x1, x2 from [1, n-1] */
        x1 = org.bouncycastle.util.BigIntegers.createRandomInRange(BigInteger.ONE, 
    			n.subtract(BigInteger.ONE), new SecureRandom());
    	x2 = org.bouncycastle.util.BigIntegers.createRandomInRange(BigInteger.ONE, 
    			n.subtract(BigInteger.ONE), new SecureRandom());
    	
        /* compute G x [x1], G x [x2] */
        pointG1 = G.multiply(x1);
        pointG2 = G.multiply(x2);
        
        /* create ZKP of x1 and x2 (Schnorr) */
        SchnorrZKP zkpx1 = generateZKP(G, n, x1, pointG1, PC_ID);
        SchnorrZKP zkpx2 = generateZKP(G, n, x2, pointG2, PC_ID);

        /* Encode pointG1, pointG2, zkpx1, zkpx2 */
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        
        byteStream.write(pointG1.getEncoded(COMPRESS_POINTS));
        byteStream.write(zkpx1.toByteArray());
        
        byteStream.write(pointG2.getEncoded(COMPRESS_POINTS));
        byteStream.write(zkpx2.toByteArray());
                
        byte[] apduData = byteStream.toByteArray();
        byteStream.close();
        return apduData;
    }
    
    private boolean JPAKE2(byte[] response) {
        /* Parse the response */
        ByteArrayInputStream stream = new ByteArrayInputStream(response);
        byte[] pointBytes = new byte[POINT_LENGTH];
        byte[] zkpBytes = new byte[ZKP_LENGTH];
        
        /* Get G3 = G x [x3], and a ZKP of x3 */
        stream.read(pointBytes, 0, POINT_LENGTH);
        pointG3 = ecCurve.decodePoint(pointBytes);
        stream.read(zkpBytes, 0, ZKP_LENGTH);
        SchnorrZKP zkpx3 = new SchnorrZKP(zkpBytes);
        
        /* Get G4 = G x [x4], and a ZKP of x4 */
        stream.read(pointBytes, 0, POINT_LENGTH);
        pointG4 = ecCurve.decodePoint(pointBytes);    
        stream.read(zkpBytes, 0, ZKP_LENGTH);
        SchnorrZKP zkpx4 = new SchnorrZKP(zkpBytes);
        
        /* Verify ZKP of x3, x4 */
        if (verifyZKP(G, pointG3, zkpx3.getV(), zkpx3.getr(), CARD_ID) ){
            // System.out.println("ZKP x3 OK.");
        } else {
            System.out.println("ZKP x3 failed.");
            return false;
        }
        if (verifyZKP(G, pointG4, zkpx4.getV(), zkpx4.getr(), CARD_ID) ){
            // System.out.println("ZKP x4 OK.");
        } else {
            System.out.println("ZKP x4 failed.");
            return false;
        }
        return true;
    }
    
    private byte[] JPAKE3(BigInteger sharedBigInt) throws IOException {
        /* Compute GA = G1 + G3 + G4 */
        GA = pointG1.add(pointG3).add(pointG4).normalize(); 
    	
        /* Compute A = (G1 + G3 + G4) x [x2*s] and a ZKP for x2*s */
        A = GA.multiply(x2.multiply(sharedBigInt).mod(n));
        SchnorrZKP zkpx2s = generateZKP(GA, n, x2.multiply(sharedBigInt).mod(n), A, PC_ID);

        /* Encode A and zkpx2s and send data to card */
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        
        byteStream.write(A.normalize().getEncoded(COMPRESS_POINTS));
        byteStream.write(zkpx2s.toByteArray());
        
        byte[] apduData = byteStream.toByteArray();
        byteStream.close();
        return apduData;
    }
    
    private boolean JPAKE4(byte[] response, BigInteger sharedBigInt){
        ByteArrayInputStream stream = new ByteArrayInputStream(response);
        byte[] pointBytes = new byte[POINT_LENGTH];
        byte[] zkpBytes = new byte[ZKP_LENGTH];
        
        /* Compute GB = G1 + G2 + G3 */
        ECPoint GB = pointG1.add(pointG2).add(pointG3).normalize();
        
        /* Get B = (G1 + G2 + G3) x [x4*s] and a ZKP for x4*s */
        stream.read(pointBytes, 0, POINT_LENGTH);
        ECPoint B = ecCurve.decodePoint(pointBytes).normalize();
        stream.read(zkpBytes, 0, ZKP_LENGTH);
        SchnorrZKP zkpx4s = new SchnorrZKP(zkpBytes);   
        
        /* Verify ZKP of x4*s */
        if (verifyZKP(GB, B, zkpx4s.getV(), zkpx4s.getr(), CARD_ID) ){
            // System.out.println("ZKP x4*s OK.");
        } else {
            System.out.println("ZKP x4*s failed.");
            return false;
        }
        
        /* Computed K = (B - (G4 x [x2*s])) x [x2] to get a shared secret */
        pointK = B.subtract(pointG4.multiply(x2.multiply(sharedBigInt))).multiply(x2).normalize();
        BigInteger K = getSHA256(pointK.normalize().getXCoord().toBigInteger());
        byte [] Karr = K.toByteArray();
        if(Karr.length > 32) {
            Karr = Arrays.copyOfRange(Karr, Karr.length-32, Karr.length);
        }
        
        
        setKeyAES(Karr);
        return true;
    }
    
  
    
    private BigInteger getSHA256(ECPoint G, ECPoint V, ECPoint D, byte[] userID) {
    	MessageDigest sha256 = null;
    	try {
    		sha256 = MessageDigest.getInstance("SHA-256");
    		
    		byte[] GBytes = G.getEncoded(true);
    		byte[] VBytes = V.getEncoded(true);
    		byte[] XBytes = D.getEncoded(true);
    		
    		// It's good practice to prepend each item with a 4-byte length
    		sha256.update(ByteBuffer.allocate(4).putInt(GBytes.length).array());
    		sha256.update(GBytes);

    		sha256.update(ByteBuffer.allocate(4).putInt(VBytes.length).array());
    		sha256.update(VBytes);

    		sha256.update(ByteBuffer.allocate(4).putInt(XBytes.length).array());
    		sha256.update(XBytes);
    		
    		sha256.update(ByteBuffer.allocate(4).putInt(userID.length).array());
    		sha256.update(userID);    	
   		
    	} catch (Exception e) {
    		e.printStackTrace();
    	}
    	return new BigInteger(sha256.digest());
    }
    
    private BigInteger getSHA256(BigInteger toHash) {
    	MessageDigest sha256 = null;
    	try {
    		sha256 = MessageDigest.getInstance("SHA-256");
    		sha256.update(toHash.toByteArray());
    	} catch (Exception e) {
    		e.printStackTrace();
    	}
    	return new BigInteger(1, sha256.digest()); // 1 for positive int
    }
    
    
    private void setKeyAES(byte[] newKey) {
        try {
        //    MessageDigest sha = MessageDigest.getInstance("SHA-1");
        //    newKey = sha.digest(newKey);
        //    newKey = Arrays.copyOf(newKey, 16); // use only first 128 bit

        //    m_aesKey = new SecretKeySpec(newKey, "AES");
            if(newKey.length > 32) {
                newKey = Arrays.copyOfRange(newKey, newKey.length-32, newKey.length);
            }
        
            m_aesKey = new SecretKeySpec(newKey, "AES");
            // System.out.println(m_aesKey.getEncoded().length);
            byte [] iv = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}; //well helll we know, fast fix!
            IvParameterSpec ivParams = new IvParameterSpec(iv);
            m_encryptCipher.init(Cipher.ENCRYPT_MODE, m_aesKey, ivParams);
            m_decryptCipher.init(Cipher.DECRYPT_MODE, m_aesKey, ivParams);
        } catch (InvalidKeyException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        } catch (InvalidAlgorithmParameterException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        //} catch (NoSuchAlgorithmException ex) {
        //    Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    private CommandAPDU secureWrapOutgoing(CommandAPDU apdu){
        byte[] apduBytes = apdu.getBytes();
        byte[] messageHeader = {(byte) counter, 0x00, 0x00, (byte) apduBytes.length};
        byte[] message = Arrays.concatenate(messageHeader, apduBytes);
        
        int toFill = 240 - message.length;
        if (toFill > 0) {
            SecureRandom sr = new SecureRandom();
            byte[] filling = sr.generateSeed(toFill);
            message = Arrays.concatenate(message, filling);
        }
        m_checksum.doFinal(message,(short) 4, (short)apduBytes.length, message, (short) 1);
        
        
        try {
            m_encryptCipher.doFinal(message, 0, message.length, message, 0);
        } catch (ShortBufferException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IllegalBlockSizeException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        } catch (BadPaddingException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        }
        return new CommandAPDU(0xB0, 0x04, 0x00, 0x00, message, 240); //please gimme 240 back
    }
    
    
    private byte[] secureUnwrapIncoming(ResponseAPDU apdu) {
        byte [] message = apdu.getData();
        try {
        m_decryptCipher.doFinal(message, 0, message.length, message, 0);
        } catch (ShortBufferException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IllegalBlockSizeException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        } catch (BadPaddingException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        if(counter != message[SESSION_COUNTER_OFFSET]) {
            System.err.println("Decryption - error - wrong counter");
            return new byte[0];
        }
        
        byte [] checksum = {0, 0};
        m_checksum.doFinal(message, (short)SESSION_DATA_OFFSET, message[SESSION_DATALENGTH_OFFSET], checksum, (short)0);
        if(checksum[0] != message[SESSION_CHECKSUM_OFFSET] || checksum[1] != message[SESSION_CHECKSUM_OFFSET+1]) {
             System.err.println("Decryption - error - wrong checksum ");
            return new byte[0];
        }

        return Arrays.copyOfRange(message, SESSION_DATA_OFFSET, SESSION_DATA_OFFSET + message[SESSION_DATALENGTH_OFFSET]);
    }
    
    
    private byte[] secureUnwapOutgoing(CommandAPDU apdu) {
        byte[] message = apdu.getData();
        try {
            m_decryptCipher.doFinal(message, 0, message.length, message, 0);
        } catch (ShortBufferException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IllegalBlockSizeException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        } catch (BadPaddingException ex) {
            Logger.getLogger(PV204Cracker.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        if (message[0] != (byte) counter){
            System.err.println("Decryption - error - wrong counter ");
            return new byte[0];
        }
        
        byte[] checksum = new byte[2];
        byte[] checksumIn = Arrays.copyOfRange(message, 1, 3);
        m_checksum.doFinal(message,(short) 4, message[SESSION_DATALENGTH_OFFSET], checksum,(short) 0);
        
        if(!java.util.Arrays.equals(checksum, checksumIn)) {
            System.err.println("Decryption - error - wrong checksum ");
            return new byte[0];
        }
        
        // CHECK CHECKSUM
        int apduLength = (message[MESSAGE_LENGTH_OFFSET] & 0xFF);
        byte[] apduBytes = Arrays.copyOfRange(message, MESSAGE_HEADER_LENGTH, MESSAGE_HEADER_LENGTH + apduLength);
        
        return apduBytes;
    } 
    
    
    private SchnorrZKP generateZKP (ECPoint G, BigInteger n, BigInteger d, ECPoint D, byte[] userID) {
            /* Generate a proof of knowledge of scalar for D = [d] x G */
            
            /* Generate a random v from [1, n-1], and compute V = [v] x G */
            BigInteger v = org.bouncycastle.util.BigIntegers.createRandomInRange(BigInteger.ONE, 
       			n.subtract(BigInteger.ONE), new SecureRandom());
            ECPoint V = G.multiply(v);
                
            BigInteger c = getSHA256(G, V, D, userID); // compute hash H(G || V || D || UserID)
            BigInteger r = v.subtract(d.multiply(c)).mod(n); // r = v-d*c mod n 
            return new SchnorrZKP(V,r);
    }
    
    public boolean verifyZKP(ECPoint generator, ECPoint X, ECPoint V, BigInteger r, byte[] userID) {
       
        /* ZKP: {V=G*v, r} */              
        BigInteger h = getSHA256(generator, V, X, userID);
       
        // Public key validation based on p. 25
        // http://cs.ucsb.edu/~koc/ccs130h/notes/ecdsa-cert.pdf
       
        // 1. X != infinity
        if (X.isInfinity()){
            return false;
        }
       
        // 2. Check x and y coordinates are in Fq, i.e., x, y in [0, q-1]
        if (X.getXCoord().toBigInteger().compareTo(BigInteger.ZERO) == -1 ||
                X.getXCoord().toBigInteger().compareTo(ecCurve.getQ().subtract(BigInteger.ONE)) == 1 ||
                X.getYCoord().toBigInteger().compareTo(BigInteger.ZERO) == -1 ||
                X.getYCoord().toBigInteger().compareTo(ecCurve.getQ().subtract(BigInteger.ONE)) == 1) {
            return false;
        }
                   
        // 3. Check X lies on the curve
        try {
            ecCurve.decodePoint(X.getEncoded(false));
        }
        catch(Exception e){
            e.printStackTrace();
            return false;
        }
       
        // 4. Check that nX = infinity.
        // It is equivalent - but more more efficient - to check the coFactor*X is not infinity
        if (X.multiply(ecSpec.getH()).isInfinity()) {
            return false;
        }
       
        // Now check if V = G*r + X*h.
        // Given that {G, X} are valid points on curve, the equality implies that V is also a point on curve.
        if (V.equals(generator.multiply(r).add(X.multiply(h.mod(n))))) {
            return true;
        }
        else {
            return false;
        }
    }
    
    private class SchnorrZKP {
    	/*
          Class which holds a number and a point corresponding to some ZKP.
        */
    	private ECPoint V = null;
    	private BigInteger r = null;
    			
    	private SchnorrZKP(ECPoint V, BigInteger r) {
            this.V = V;
            this.r = r;  
    	}
        
        private SchnorrZKP(byte[] encoded){
            /*
              Constructor which decodes a point and a number from byte array.
            */
            this.V = ecCurve.decodePoint(Arrays.copyOfRange(encoded, 0, POINT_LENGTH)).normalize();
            this.r = new BigInteger(1,Arrays.copyOfRange(encoded, POINT_LENGTH, ZKP_LENGTH));
        }
        
	private ECPoint getV() {
            return V;
    	}
    	
    	private BigInteger getr() {
            return r;
    	}
        
        private byte[] toByteArray() throws IOException {
            /*
              Encodes ZKP (the point and the number) to bytes
            */
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byteStream.write(this.getV().normalize().getEncoded(COMPRESS_POINTS));
            byte[] array = this.getr().toByteArray();
            if(array.length > 32) {
                array = java.util.Arrays.copyOfRange(array, array.length-32, array.length);
            }
            int diff = BIGINT_LENGTH - array.length;
            if (diff > 0){
                byteStream.write(new byte[diff]);
            }
            byteStream.write(array);
            byte[] retBytes = byteStream.toByteArray();
            byteStream.close();
            return retBytes;
        }
    }

    boolean check_pin(CardManager cardMngr, byte[] pin) throws Exception {
        counter = (byte) 0x00;
        try {
            if (!CreateSecureChannel(cardMngr, pin)) {
                cardMngr.transmit(deselectAPDU());
            }
        }catch (InvalidKeyException e){
            return false;
        }

        String testMessage = "whate we have got here... Is a failiure in communication";
        byte[] testBytes = testMessage.getBytes();
        /* TEST - HASH with card*/
        CommandAPDU command4 = new CommandAPDU(0xB0, INS_HASH, 0x0, 0x0, testBytes, 32);
        ResponseAPDU response4;
        try {
            response4 = cardMngr.transmit(secureWrapOutgoing(command4));
        }catch (IllegalStateException e){
            return false;
        }
        counter++;
        try {
            byte[] hash = secureUnwrapIncoming(response4);
        }catch (ArrayIndexOutOfBoundsException exception){
            return false;
        }
        return true;
    }
}
