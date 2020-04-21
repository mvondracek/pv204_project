package applets;

import javacard.framework.*;
import javacard.security.*;
import javacardx.crypto.*;
import opencrypto.jcmathlib.*;

public class AlmostSecureApplet extends javacard.framework.Applet {
    
    
    // MAIN INSTRUCTION CLASS

    final static byte CLA_SIMPLEAPPLET = (byte) 0xB0;

    // INSTRUCTIONS
    final static byte INS_ENCRYPT = (byte) 0x50;
    final static byte INS_DECRYPT = (byte) 0x51;
    final static byte INS_SETKEY = (byte) 0x52;
    final static byte INS_HASH = (byte) 0x53;
    final static byte INS_RANDOM = (byte) 0x54;
    final static byte INS_VERIFYPIN = (byte) 0x55;
    final static byte INS_SETPIN = (byte) 0x56;
    final static byte INS_RETURNDATA = (byte) 0x57;
    final static byte INS_SIGNDATA = (byte) 0x58;

    final static short ARRAY_LENGTH = (short) 0xff;
    final static byte AES_BLOCK_LENGTH = (short) 0x16;

    final static short SW_BAD_TEST_DATA_LEN = (short) 0x6680;
    final static short SW_KEY_LENGTH_BAD = (short) 0x6715;
    final static short SW_CIPHER_DATA_LENGTH_BAD = (short) 0x6710;
    final static short SW_OBJECT_NOT_AVAILABLE = (short) 0x6711;
    final static short SW_BAD_PIN = (short) 0x6900;

    final static short SW_Exception = (short) 0xff01;
    final static short SW_ArrayIndexOutOfBoundsException = (short) 0xff02;
    final static short SW_ArithmeticException = (short) 0xff03;
    final static short SW_ArrayStoreException = (short) 0xff04;
    final static short SW_NullPointerException = (short) 0xff05;
    final static short SW_NegativeArraySizeException = (short) 0xff06;
    final static short SW_CryptoException_prefix = (short) 0xf100;
    final static short SW_SystemException_prefix = (short) 0xf200;
    final static short SW_PINException_prefix = (short) 0xf300;
    final static short SW_TransactionException_prefix = (short) 0xf400;
    final static short SW_CardRuntimeException_prefix = (short) 0xf500;

    private AESKey m_aesKey = null;
    private Cipher m_encryptCipher = null;
    private Cipher m_decryptCipher = null;
    private RandomData m_secureRandom = null;
    protected MessageDigest m_hash = null;
    private OwnerPIN m_pin = null;
    private byte[] m_rawpin = null;
    private Signature m_sign = null;
    private KeyPair m_keyPair = null;
    private Key m_privateKey = null;
    private Key m_publicKey = null;
    
    //EC and schnorr
    protected ECConfig        ecc = null;
    protected ECCurve         curve = null;
    protected ECPoint         Gen = null;
    protected ECPoint         X3 = null;
    protected ECPoint         X4 = null;
    //schnorr
    byte[] mID = {'c', 'a', 'r', 'd'};
    byte[] theirID = {'u', 's', 'e', 'r'};
    

    // TEMPORARRY ARRAY IN RAM
    private byte m_ramArray[] = null;
    // PERSISTENT ARRAY IN EEPROM
    private byte m_dataArray[] = null;

    /**
     * SimpleApplet default constructor Only this class's install method should
     * create the applet object.
     */
    protected AlmostSecureApplet(byte[] buffer, short offset, byte length) {
        // data offset is used for application specific parameter.
        // initialization with default offset (AID offset).
        short dataOffset = offset;
        boolean isOP2 = false;

        if (length > 9) {
            // Install parameter detail. Compliant with OP 2.0.1.

            // | size | content
            // |------|---------------------------
            // |  1   | [AID_Length]
            // | 5-16 | [AID_Bytes]
            // |  1   | [Privilege_Length]
            // | 1-n  | [Privilege_Bytes] (normally 1Byte)
            // |  1   | [Application_Proprietary_Length]
            // | 0-m  | [Application_Proprietary_Bytes]
            // shift to privilege offset
            dataOffset += (short) (1 + buffer[offset]);
            // finally shift to Application specific offset
            dataOffset += (short) (1 + buffer[dataOffset]);

            // go to proprietary data
            dataOffset++;

            m_dataArray = new byte[ARRAY_LENGTH];
            Util.arrayFillNonAtomic(m_dataArray, (short) 0, ARRAY_LENGTH, (byte) 0);

            // CREATE AES KEY OBJECT
            m_aesKey = (AESKey) KeyBuilder.buildKey(KeyBuilder.TYPE_AES, KeyBuilder.LENGTH_AES_256, false);
            // CREATE OBJECTS FOR CBC CIPHERING
            m_encryptCipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);
            m_decryptCipher = Cipher.getInstance(Cipher.ALG_AES_BLOCK_128_CBC_NOPAD, false);

            // CREATE RANDOM DATA GENERATORS
            m_secureRandom = RandomData.getInstance(RandomData.ALG_SECURE_RANDOM);

            // TEMPORARY BUFFER USED FOR FAST OPERATION WITH MEMORY LOCATED IN RAM
            m_ramArray = JCSystem.makeTransientByteArray((short) 260, JCSystem.CLEAR_ON_DESELECT);

            // SET KEY VALUE
            m_aesKey.setKey(m_dataArray, (short) 0);

            // INIT CIPHERS WITH NEW KEY
            m_encryptCipher.init(m_aesKey, Cipher.MODE_ENCRYPT);
            m_decryptCipher.init(m_aesKey, Cipher.MODE_DECRYPT);

            m_pin = new OwnerPIN((byte) 5, (byte) 4); // 5 tries, 4 digits in pin
            m_pin.update(m_dataArray, (byte) 0, (byte) 4); // set initial random pin
            
            m_rawpin = new byte [] {0x01, 0x02, 0x03, 0x04};
            // CREATE RSA KEYS AND PAIR 
            m_keyPair = new KeyPair(KeyPair.ALG_RSA_CRT, KeyBuilder.LENGTH_RSA_2048);
            m_keyPair.genKeyPair(); // Generate fresh key pair on-card
            m_publicKey = m_keyPair.getPublic();
            m_privateKey = m_keyPair.getPrivate();
            // SIGNATURE ENGINE    
            m_sign = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
            // INIT WITH PRIVATE KEY
            m_sign.init(m_privateKey, Signature.MODE_SIGN);

            // INIT HASH ENGINE
            m_hash = MessageDigest.getInstance(MessageDigest.ALG_SHA_256, false);

            // update flag
            isOP2 = true;
            
            //ECC
            ecc = new ECConfig((short) 256);
            curve = new ECCurve(false, SecP256r1.p, SecP256r1.a, SecP256r1.b, SecP256r1.G, SecP256r1.r);
            
            X3 = new ECPoint(curve, ecc.ech); //X3 and X4 points as per example
            X4 = new ECPoint(curve, ecc.ech);
            Gen = new ECPoint(curve, ecc.ech);
            Gen.setW(SecP256r1.G, (short)0, (short)65);

            mID = new byte[]{'c', 'a', 'r', 'd'};
            theirID = new byte[]{'u', 's', 'e', 'r'};
           
        } 

        // register this instance
        register();
    }

    /**
     * Method installing the applet.
     *
     * @param bArray the array containing installation parameters
     * @param bOffset the starting offset in bArray
     * @param bLength the length in bytes of the data parameter in bArray
     */
    public static void install(byte[] bArray, short bOffset, byte bLength) throws ISOException {
        // applet  instance creation 
        new AlmostSecureApplet(bArray, bOffset, bLength);
    }

    /**
     * Select method returns true if applet selection is supported.
     *
     * @return boolean status of selection.
     */
    public boolean select() {
        clearSessionData();
        
        return true;
    }

    /**
     * Deselect method called by the system in the deselection process.
     */
    public void deselect() {
        clearSessionData();
    }

    /**
     * Method processing an incoming APDU.
     *
     * @see APDU
     * @param apdu the incoming APDU
     * @exception ISOException with the response bytes defined by ISO 7816-4
     */
    public void process(APDU apdu) throws ISOException {
        // get the buffer with incoming APDU
        byte[] apduBuffer = apdu.getBuffer();

        // ignore the applet select command dispached to the process
        if (selectingApplet()) {
            return;
        }

        try {
            // APDU instruction parser
            if (apduBuffer[ISO7816.OFFSET_CLA] == CLA_SIMPLEAPPLET) {
                switch (apduBuffer[ISO7816.OFFSET_INS]) {
                    case INS_SETKEY:
                        SetKey(apdu);
                        break;
                    case INS_ENCRYPT:
                        Encrypt(apdu);
                        break;
                    case INS_DECRYPT:
                        Decrypt(apdu);
                        break;
                    case INS_HASH:
                        Hash(apdu);
                        break;
                    case INS_RANDOM:
                        Random(apdu);
                        break;
                    case INS_VERIFYPIN:
                        VerifyPIN(apdu);
                        break;
                    case INS_SETPIN:
                        SetPIN(apdu);
                        break;
                    case INS_RETURNDATA:
                        ReturnData(apdu);
                        break;
                    case INS_SIGNDATA:
                        Sign(apdu);
                        break;
                    default:
                        // The INS code is not supported by the dispatcher
                        ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
                        break;
                }
            } else {
                ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
            }

            // Capture all reasonable exceptions and change into readable ones (instead of 0x6f00) 
        } catch (ISOException e) {
            throw e; // Our exception from code, just re-emit
        } catch (ArrayIndexOutOfBoundsException e) {
            ISOException.throwIt(SW_ArrayIndexOutOfBoundsException);
        } catch (ArithmeticException e) {
            ISOException.throwIt(SW_ArithmeticException);
        } catch (ArrayStoreException e) {
            ISOException.throwIt(SW_ArrayStoreException);
        } catch (NullPointerException e) {
            ISOException.throwIt(SW_NullPointerException);
        } catch (NegativeArraySizeException e) {
            ISOException.throwIt(SW_NegativeArraySizeException);
        } catch (CryptoException e) {
            ISOException.throwIt((short) (SW_CryptoException_prefix | e.getReason()));
        } catch (SystemException e) {
            ISOException.throwIt((short) (SW_SystemException_prefix | e.getReason()));
        } catch (PINException e) {
            ISOException.throwIt((short) (SW_PINException_prefix | e.getReason()));
        } catch (TransactionException e) {
            ISOException.throwIt((short) (SW_TransactionException_prefix | e.getReason()));
        } catch (CardRuntimeException e) {
            ISOException.throwIt((short) (SW_CardRuntimeException_prefix | e.getReason()));
        } catch (Exception e) {
            ISOException.throwIt(SW_Exception);
        }
    }

    void clearSessionData() {
        // E.g., fill sesssion data in RAM with zeroes
        Util.arrayFillNonAtomic(m_ramArray, (short) 0, (short) m_ramArray.length, (byte) 0);
        ecc.refreshAfterReset();
        // Or better fill with random data
        m_secureRandom.generateData(m_ramArray, (short) 0, (short) m_ramArray.length);
    }
    
    // SET ENCRYPTION & DECRYPTION KEY
    void SetKey(APDU apdu) {
        byte[] apdubuf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();

        // CHECK EXPECTED LENGTH
        if ((short) (dataLen * 8) != KeyBuilder.LENGTH_AES_256) {
            ISOException.throwIt(SW_KEY_LENGTH_BAD);
        }

        // SET KEY VALUE
        m_aesKey.setKey(apdubuf, ISO7816.OFFSET_CDATA);

        // INIT CIPHERS WITH NEW KEY
        m_encryptCipher.init(m_aesKey, Cipher.MODE_ENCRYPT);
        m_decryptCipher.init(m_aesKey, Cipher.MODE_DECRYPT);
    }
    
    
    void jpakeResponse1(APDU apdu) {
        
        //ALICESIM
        ECPoint X1 = new ECPoint(curve, ecc.ech);
        ECPoint X2 = new ECPoint(curve, ecc.ech);
        
        
        X1.randomize();
        X2.randomize();
        
        opencrypto.jcmathlib.Integer x1 = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
        X1.getS(x1.getMagnitude_b(), (short)0);
   
        opencrypto.jcmathlib.Integer x2 = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
        X2.getS(x2.getMagnitude_b(), (short)0);
        
        SchnorrZKP zkpX1 = new SchnorrZKP();
        SchnorrZKP zkpX2 = new SchnorrZKP();
        zkpX1.generateZKP(Gen, X1, x1, theirID);
        zkpX2.generateZKP(Gen, X2, x2, theirID);
        //----------------------
        
        X3.randomize();
        X4.randomize();
        
        opencrypto.jcmathlib.Integer x3 = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
        X3.getS(x3.getMagnitude_b(), (short)0);
   
        opencrypto.jcmathlib.Integer x4 = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
        X4.getS(x4.getMagnitude_b(), (short)0);
        
        SchnorrZKP zkpX3 = new SchnorrZKP();
        SchnorrZKP zkpX4 = new SchnorrZKP();
        zkpX3.generateZKP(Gen, X3, x3, mID);
        zkpX4.generateZKP(Gen, X3, x4, mID);
        
        //ALICESIM
        //check ID we need valid identity but whatever, this can be static, ID is a public information
        if (verifyZKP(Gen, X3, zkpX3.getV(), zkpX3.getr(), mID, (short)4) && verifyZKP(Gen, X4, zkpX4.getV(), zkpX4.getr(), mID, (short)4)) {
            //ok this works
            byte[] kkt = new byte[] {0x01};
        }
        //----------------------------------
        
        if (verifyZKP(Gen, X1, zkpX1.getV(), zkpX1.getr(), theirID, (short)4) && verifyZKP(Gen, X2, zkpX2.getV(), zkpX2.getr(), theirID, (short)4)) {
            //ok this works
            byte[] kkt = new byte[] {0x01};
        }
        
        //should be common
        opencrypto.jcmathlib.Integer n = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
        n.fromByteArrayAsNat(SecP256r1.n, (short)0, (short)32);
        opencrypto.jcmathlib.Integer s = new opencrypto.jcmathlib.Integer(m_rawpin, (short)0, (short)4, ecc.bnh); //password = pin
        
        //ALICESIM
        opencrypto.jcmathlib.Integer x2s = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
        x2s.clone(x2);
        //-------------------------------------
        
        opencrypto.jcmathlib.Integer x4s = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
        x4s.clone(x4);
        

//STEP 2!!!!!
        
        //ALICESIM
        ECPoint GA = new ECPoint(curve, ecc.ech);
        ECPoint A = new ECPoint(curve, ecc.ech);
        GA.copy(X1);
        GA.add(X3);
        GA.add(X4);
        A.copy(GA);
        
        x2s.multiply(s);
        x2s.modulo(n); //x2 is now forever x2.multiply(s).mod(n)
        
        A.multiplication(x2s.getMagnitude());
        
        SchnorrZKP zkpX2s = new SchnorrZKP();
        zkpX2s.generateZKP(GA, A, x2s, mID);
        //-------------------
        
        ECPoint GB = new ECPoint(curve, ecc.ech);
        ECPoint B = new ECPoint(curve, ecc.ech);
        GB.copy(X1);
        GB.add(X2);
        GB.add(X3);
        B.copy(GB);
        
        x4s.multiply(s);
        x4s.modulo(n); //x2 is now forever x2.multiply(s).mod(n)
        
        B.multiplication(x4s.getMagnitude());
        
        SchnorrZKP zkpX4s = new SchnorrZKP();
        zkpX4s.generateZKP(GB, B, x4s, mID);
        
        
        //CHECK!!!
        //ALICESIM .
        if (verifyZKP(GB, B, zkpX2s.getV(), zkpX4s.getr(), mID, (short)4)) {
            //ok this works
            byte[] kkt = new byte[] {0x01};
        }
        //----------------------
        
        if (verifyZKP(GA, A, zkpX2s.getV(), zkpX2s.getr(), theirID, (short)4)) {
            //ok this works
            byte[] kkt = new byte[] {0x01};
        }
        
        
        //ALICESIM
        //opencrypto.jcmathlib.Integer Ka = new opencrypto.jcmathlib.Integer((short)64, ecc.bnh);
        X4.multiplication(x2s.getMagnitude());
        X4.negate();
        B.add(X4);
        B.multiplication(x2.getMagnitude());
        //-----------------------------
        
        //opencrypto.jcmathlib.Integer Kb = new opencrypto.jcmathlib.Integer((short)64, ecc.bnh);
        X2.multiplication(x4s.getMagnitude());
        X2.negate();
        A.add(X2);
        A.multiplication(x4.getMagnitude());
        
        if(A.isEqual(B)) {
            //WE WON
            byte[] kkt = new byte[] {0x01};
        }
        
    }

    // ENCRYPT INCOMING BUFFER
    void Encrypt(APDU apdu) {
        byte[] apdubuf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();

        // CHECK EXPECTED LENGTH (MULTIPLY OF AES BLOCK LENGTH)
        if ((dataLen % 16) != 0) {
            ISOException.throwIt(SW_CIPHER_DATA_LENGTH_BAD);
        }

        // ENCRYPT INCOMING BUFFER
        m_encryptCipher.doFinal(apdubuf, ISO7816.OFFSET_CDATA, dataLen, m_ramArray, (short) 0);
        // NOTE: In-place encryption directly with apdubuf as output can be performed. m_ramArray used to demonstrate Util.arrayCopyNonAtomic

        // COPY ENCRYPTED DATA INTO OUTGOING BUFFER
        Util.arrayCopyNonAtomic(m_ramArray, (short) 0, apdubuf, ISO7816.OFFSET_CDATA, dataLen);

        // SEND OUTGOING BUFFER
        apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, dataLen);
    }

    // DECRYPT INCOMING BUFFER
    void Decrypt(APDU apdu) {
        byte[] apdubuf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();

        // CHECK EXPECTED LENGTH (MULTIPLY OF AES BLOCK LENGTH)
        if ((dataLen % 16) != 0) {
            ISOException.throwIt(SW_CIPHER_DATA_LENGTH_BAD);
        }

        // ENCRYPT INCOMING BUFFER
        m_decryptCipher.doFinal(apdubuf, ISO7816.OFFSET_CDATA, dataLen, m_ramArray, (short) 0);

        // COPY ENCRYPTED DATA INTO OUTGOING BUFFER
        Util.arrayCopyNonAtomic(m_ramArray, (short) 0, apdubuf, ISO7816.OFFSET_CDATA, dataLen);

        // SEND OUTGOING BUFFER
        apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, dataLen);
    }

    // HASH INCOMING BUFFER
    void Hash(APDU apdu) {
        byte[] apdubuf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();

        if (m_hash != null) {
            m_hash.doFinal(apdubuf, ISO7816.OFFSET_CDATA, dataLen, m_ramArray, (short) 0);
        } else {
            ISOException.throwIt(SW_OBJECT_NOT_AVAILABLE);
        }

        // COPY ENCRYPTED DATA INTO OUTGOING BUFFER
        Util.arrayCopyNonAtomic(m_ramArray, (short) 0, apdubuf, ISO7816.OFFSET_CDATA, m_hash.getLength());

        // SEND OUTGOING BUFFER
        apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, m_hash.getLength());
    }

    // GENERATE RANDOM DATA
    void Random(APDU apdu) {
        jpakeResponse1(apdu);
        byte[] apdubuf = apdu.getBuffer();

        // GENERATE DATA
        short randomDataLen = apdubuf[ISO7816.OFFSET_P1];
        m_secureRandom.generateData(apdubuf, ISO7816.OFFSET_CDATA, randomDataLen);

        // SEND OUTGOING BUFFER
        apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, randomDataLen);
    }

    // VERIFY PIN
    void VerifyPIN(APDU apdu) {
        byte[] apdubuf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();

        // VERIFY PIN
        if (m_pin.check(apdubuf, ISO7816.OFFSET_CDATA, (byte) dataLen) == false) {
            ISOException.throwIt(SW_BAD_PIN);
        }
    }

    // SET PIN 
    // Be aware - this method will allow attacker to set own PIN - need to protected. 
    // E.g., by additional Admin PIN or all secret data of previous user needs to be reased 
    void SetPIN(APDU apdu) {
        byte[] apdubuf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();

        // SET NEW PIN
        m_pin.update(apdubuf, ISO7816.OFFSET_CDATA, (byte) dataLen);
    }

    // RETURN INPU DATA UNCHANGED
    void ReturnData(APDU apdu) {
        byte[] apdubuf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();

        apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, dataLen);
    }

    void Sign(APDU apdu) {
        byte[] apdubuf = apdu.getBuffer();
        short dataLen = apdu.setIncomingAndReceive();
        short signLen = 0;

        // SIGN INCOMING BUFFER
        signLen = m_sign.sign(apdubuf, ISO7816.OFFSET_CDATA, (byte) dataLen, m_ramArray, (byte) 0);

        // COPY SIGNED DATA INTO OUTGOING BUFFER
        Util.arrayCopyNonAtomic(m_ramArray, (short) 0, apdubuf, ISO7816.OFFSET_CDATA, signLen);

        // SEND OUTGOING BUFFER
        apdu.setOutgoingAndSend(ISO7816.OFFSET_CDATA, signLen);
    }
    
     public boolean verifyZKP(ECPoint G, ECPoint V, ECPoint X, opencrypto.jcmathlib.Integer r, byte[] userID, short userIDLength) {
    	
    	/* ZKP: {V=G*v, r} */    	    	
    	//BigInteger h = getSHA256(generator, V, X, userID);
        
        opencrypto.jcmathlib.Integer h = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
        AlmostSecureApplet.getHash(m_hash, G, V, X, userID, userIDLength, h.getMagnitude_b(), (short)0);
        
    	// Public key validation based on p. 25
    	// http://cs.ucsb.edu/~koc/ccs130h/notes/ecdsa-cert.pdf
    	
        //We will count on nonzero
    	// 1. X != infinity
    	//if (X.isInfinity()){
    	//	return false;
    	//}
    	
    	// 2. Check x and y coordinates are in Fq, i.e., x, y in [0, q-1]
        //skip for now IDK what the F is this, probably order of G
    	//if (X.getX().toBigInteger().compareTo(BigInteger.ZERO) == -1 ||
    	//		X.getX().toBigInteger().compareTo(q.subtract(BigInteger.ONE)) == 1 ||
    	//		X.getY().toBigInteger().compareTo(BigInteger.ZERO) == -1 ||
    	//		X.getY().toBigInteger().compareTo(q.subtract(BigInteger.ONE)) == 1) {
    	//	return false;
    	//}
        
    	// 3. Check X lies on the curve
    	//try {
    	//	ecCurve.decodePoint(X.getEncoded());
    	//}
    	
    	// 4. Check that nX = infinity.
    	// It is equivalent - but more more efficient - to check the coFactor*X is not infinity
    	//if (X.multiply(coFactor).isInfinity()) { 
    	//	return false;
    	//}
    	
        
        
    	// Now check if V = G*r + X*h. 
        ECPoint X1 = new ECPoint(curve, ecc.ech);
        ECPoint G1 = new ECPoint(curve, ecc.ech);
        X1.copy(X);
        G1.copy(G);
        
        X1.multiplication(h.getMagnitude());
        G1.multiplication(r.getMagnitude());
        
        X1.add(G1);
        
        
        
        
    	// Given that {G, X} are valid points on curve, the equality implies that V is also a point on curve.
    	return X.isEqual(V);
    }

    public static final short getHash(MessageDigest hash, ECPoint G, ECPoint V, ECPoint X, byte[] userID, short userIDLength, byte[] buffer, short offset) {
        
                short compCSize = 33; //size of compressed form of curve
    		byte [] VCompressed = new byte[compCSize];
                V.getCompressed(VCompressed, (short)0);
    		byte [] XCompressed = new byte[compCSize];
                X.getCompressed(XCompressed, (short)0);
                byte [] GCompressed = new byte[compCSize];
                G.getCompressed(GCompressed, (short)0);
                
                
                byte [] shaPaddingCompCurve = {0x00, 0x00, (byte)((compCSize << 8) & 0xff), (byte)((compCSize) & 0xff)}; //padding required by SHA (and forced in our J-PAKE reference implementation)
                                                          //that contains 4 bytes of size of what we are going to add
                byte [] shaPaddingUserID = {0x00, 0x00, (byte)((userIDLength << 8) & 0xff), (byte)((userIDLength) & 0xff)};
                                                              
                hash.reset();
                hash.update(shaPaddingCompCurve, (short)0, (short)4);
                hash.update(GCompressed, (short)0, (short)33); //we do not have to pepend 0x03

    		hash.update(shaPaddingCompCurve, (short)0, (short)4);
                hash.update(VCompressed, (short)0, compCSize);
                
                hash.update(shaPaddingCompCurve, (short)0, (short)4);
                hash.update(XCompressed, (short)0, compCSize);
                
                hash.update(shaPaddingUserID, (short)0, (short)4);
                hash.update(userID, (short)0, userIDLength);
                
                hash.doFinal(userID, (short)0, userIDLength, buffer, offset);
                
   	return hash.getLength();
    }

    public opencrypto.jcmathlib.Integer getHash(opencrypto.jcmathlib.Integer K) {

    	m_hash.reset();
        opencrypto.jcmathlib.Integer ret = new opencrypto.jcmathlib.Integer((short)m_hash.getLength(), ecc.bnh);
        m_hash.doFinal(K.getMagnitude_b(), (short)0, K.getSize(), ret.getMagnitude_b(), (short)0);
        //m_hash.update(K.getMagnitude_b(), (short)0, K.getSize());
        

    	return ret; // 1 for positive int
    }
    
    protected class SchnorrZKP {
    	
    	private ECPoint V = null;
    	private opencrypto.jcmathlib.Integer r = null;
        short m_ramoffset = 0; //maybe will be used once
    			
    	private SchnorrZKP () {
                r = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
                V = new ECPoint(curve, ecc.ech);
    	}
    	
    	private void generateZKP (ECPoint G, ECPoint X, opencrypto.jcmathlib.Integer x, byte[] userID) {

                opencrypto.jcmathlib.Integer h = new opencrypto.jcmathlib.Integer((short)64, ecc.bnh); //we need to store multiplication
                
                V.randomize(); //effectively generating random v
                AlmostSecureApplet.getHash(m_hash, G, V, X, userID, (short)userID.length, h.getMagnitude_b(), (short)32);

                opencrypto.jcmathlib.Integer v = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
                opencrypto.jcmathlib.Integer n = new opencrypto.jcmathlib.Integer((short)32, ecc.bnh);
                n.fromByteArrayAsNat(SecP256r1.n, (short)0, (short)32);
                
                opencrypto.jcmathlib.Integer x2 = new opencrypto.jcmathlib.Integer((short)64, ecc.bnh); //another dirty trick, why this will never work on real javacard
                x2.clone(x);
                
                //extracting private keys
                V.getS(v.getMagnitude_b(), (short)0);
                X.getS(v.getMagnitude_b(), (short)0);
                
                // r = v-x*h mod n    ==   (-h*x + v) mod n                
                h.multiply(x2);
                h.negate();
                
                h.add(v);
                h.modulo(n); //we still have the negative value of mod
                h.add(n); //so we need o add this
                r = h;
        }
    	
    	private ECPoint getV() {
    		return V;
    	}
    	
    	private opencrypto.jcmathlib.Integer getr() {
    		return r;
    	}
    	
    }
}

