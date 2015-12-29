/********************************************************************************
* FILE: Amy.java
*	(CS2105 Assignment #3 AY 2015/16 - Semester I)
*
* AUTHOR: MunKeat
* Acknowledgement
* 	Skeletal frame adopted from CS2105 Assignment 03 [Refer to [Packet] Assignment 3]
*
* DATE: 02-Nov-2015
*
* PURPOSE: Amy.java performs the following:
* Procedure:
* (1) Amy has to verify Bryan's key
* (2) Amy sends Bryan session (AES) key generated
* (3) Amy receives messages from Bryan (assuming expected number of lines are 10),
*	decrypts and saves them to file (msgs.txt)
********************************************************************************/

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

import javax.crypto.*;

public class Amy {
	// File to store received and decrypted messages
	public static final String MESSAGE_FILE = "msgs.txt";
	// Number of lines expected from Bryan
	public static final Integer LINES_EXPECTED = 10;

	// Socket used to connect to Bryan
	private Socket connectionSkt;
	// Send session key to Bryan
	private ObjectOutputStream toBryan;
	// Read encrypted messages from Bryan
	private ObjectInputStream fromBryan;
	private Crypto crypto;

	public static void main(String[] args) {
		// Check if the number of command line argument is 2
		if (args.length != 2) {
			System.err.println("Usage: java Amy <BryanIP> <BryanPort>");
			System.exit(1);
		}

		String IPAddress = args[0];
		Integer port = Integer.parseInt(args[1]);

		Amy amy = new Amy(IPAddress, port);
		amy.sendSessionKey();
		amy.receiveMessages();
		System.out.println("Termination of Amy/Bryan messaging...");
	}

	/********************************************************************************
	 * CONSTRUCTOR: Amy.java
	 ********************************************************************************/
	public Amy(String IPAddress, int port) {
		// Connect to Bryan
		try {
			connectionSkt = new Socket(IPAddress, port);
		} catch (IOException e) {
			System.out.println("Error: Unable to constructing a socket for Amy based on parameter provided.");
			e.printStackTrace();
			System.exit(1);
		}
		// Get input/output stream to receive/send object
		try {
			toBryan = new ObjectOutputStream(this.connectionSkt.getOutputStream());
			fromBryan = new ObjectInputStream(this.connectionSkt.getInputStream());
		} catch (IOException e) {
			System.out.println("Error: Cannot get input/output streams");
			e.printStackTrace();
			System.exit(1);
		}

		try {
			PublicKey tentativePubKey = (PublicKey)fromBryan.readObject();
			byte[] encryptedDigest = (byte[])fromBryan.readObject();
			crypto = new Crypto(tentativePubKey, encryptedDigest);
		} catch (Exception e) {
			System.out.println("Error retrieving public key and digest from Bryan...");
			e.printStackTrace();
			System.exit(1);
		}
	}

	/********************************************************************************
	 * METHODS: Amy.java
	 ********************************************************************************/
	// Send session key to Bryan
	public void sendSessionKey() {
		if (crypto != null) {
			try {
				toBryan.writeObject(crypto.getSessionKey());
				toBryan.flush();
				System.out.println("Sealed session key sent successfully to Bryan.");
			} catch (IOException e) {
				System.out.println("Unable to send sealed session key to Bryan.");
				e.printStackTrace();
				System.exit(1);
			}
		} else {
			System.out.println("Crypto is null!");
		}
	}

	// Receive messages one by one from Bryan, decrypt and write to file
	public void receiveMessages() {
		PrintWriter filewriter = null;
		try {
			filewriter = new PrintWriter(MESSAGE_FILE);
		} catch (FileNotFoundException e) {
			System.out.println(MESSAGE_FILE + " not found...");
			e.printStackTrace();
			System.exit(1);
		}

		for (int i = 0; i < LINES_EXPECTED; i++) {
			try {
				SealedObject encryptedBryan = (SealedObject) fromBryan.readObject();
				String decryptedLine = crypto.decryptMsg(encryptedBryan);
				filewriter.println(decryptedLine);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		filewriter.close();

		try {
			connectionSkt.close();
		} catch (IOException e) {
			System.out.println("Unable to close socket properly...");
			e.printStackTrace();
			System.exit(1);
		}
	}

	class Crypto {
		// File that contains Bryan' public key
		public static final String PUBLIC_BERISIGN_KEY_FILE = "berisign.pub";

		// Bryan's public key, to be read from file
		private PublicKey pubKey;
		// Amy generates a new session key for each communication session
		private SecretKey sessionKey;

		/********************************************************************************
		 * CONSTRUCTOR: Crypto.java
		 ********************************************************************************/
		public Crypto(PublicKey tentative, byte[] digest) {
			// Read Bryan's public key
			MessageDigest md5 = null;
			try {
				md5 = MessageDigest.getInstance("MD5");
			} catch (NoSuchAlgorithmException e1) {
				System.out.println("Error instantiating MessageDigest...");
				e1.printStackTrace();
				System.exit(1);
			}
			md5.update("bryan".getBytes(StandardCharsets.US_ASCII));
			md5.update(tentative.getEncoded());

			byte[] obtainedDigest = md5.digest();

			//Extract Berisign's digest
			byte[] unencryptedDigest = null;

			File privKeyFile = new File(PUBLIC_BERISIGN_KEY_FILE);
			PublicKey berisign = null;
			if (privKeyFile.exists() && !privKeyFile.isDirectory()) {
				try {
					ObjectInputStream ois = new ObjectInputStream(new FileInputStream(PUBLIC_BERISIGN_KEY_FILE));
					berisign = (PublicKey) ois.readObject();
					ois.close();
				} catch (IOException oie) {
					System.out.println("Error reading public key from " + PUBLIC_BERISIGN_KEY_FILE + " file");
					System.exit(1);
				} catch (ClassNotFoundException cnfe) {
					System.out.println("Cannot typecast to class PublicKey");
					System.exit(1);
				}
				System.out.println("Public Berisign key successfully read from file " + PUBLIC_BERISIGN_KEY_FILE);
			} else {
				System.out.println("Alice cannot find RSA public key.");
				System.exit(1);
			}

			Cipher cipher = null;

			try {
				cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
				cipher.init(Cipher.DECRYPT_MODE, berisign);
			} catch (Exception e1) {
				System.out.println("Error initialising cipher.");
				e1.printStackTrace();
				System.exit(1);
			}

			try {
				unencryptedDigest = cipher.doFinal(digest);
			} catch (Exception e1) {
				System.out.println("Error extracting digest...");
				e1.printStackTrace();
				System.exit(1);
			}

			// Verify that public key == digest
			if(!Arrays.equals(obtainedDigest, unencryptedDigest)) {
				System.out.println("Obtained: " + Arrays.toString(obtainedDigest));
				System.out.println("Original: " + Arrays.toString(unencryptedDigest));
				System.out.println();
				System.out.println("Error:MD5 signature does not match");

				System.exit(1);
			} else {
				pubKey = tentative;
			}

			// Generate session key dynamically
			KeyGenerator keyGen = null;
			try {
				// Suggested AES key length of 128 bits
				keyGen = KeyGenerator.getInstance("AES");
			} catch (NoSuchAlgorithmException e) {
				System.out.println("No such algorithm for generating a dynamic session key.");
				e.printStackTrace();
				System.exit(1);
			}
			keyGen.init(128); // for example
			sessionKey = keyGen.generateKey();
		}

		/********************************************************************************
		 * METHODS: Crypto.java
		 ********************************************************************************/
		// Seal session key with RSA public key in a SealedObject and return
		public SealedObject getSessionKey() {
			SealedObject sealedSessionKey = null;
			Cipher cipher = null;

			// RSA imposes size restriction on encrypted object (117 bytes).
			// Instead of sealing a Key object which is over size restriction,
			// encrypt AES key in its byte format (using getEncoded() method).
			byte[] byteVersionSessionKey = sessionKey.getEncoded();

			try {
				//Amy must use the same RSA key/transformation as Bryan specified
				cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
				//Initialise cipher with Bryan's public key
				cipher.init(Cipher.ENCRYPT_MODE, this.pubKey);
			} catch (Exception e) {
				System.out.println("Error creating cipher.");
				System.exit(1);
			}

			try {
				sealedSessionKey = new SealedObject(byteVersionSessionKey, cipher);
			} catch (Exception e) {
				System.out.println("Error sealing session key.");
				System.exit(1);
			}
			return sealedSessionKey;
		}

		// Decrypt and extract a message from SealedObject
		public String decryptMsg(SealedObject encryptedMsgObj) {
			String plainText = null;
			Cipher cipher = null;

			try {
				// Amy and Bryan use the same AES key/transformation
				cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
				cipher.init(Cipher.DECRYPT_MODE, this.sessionKey);
			} catch (Exception e) {
				System.out.println("Error creating cipher for decryption.");
				e.printStackTrace();
				System.exit(1);
			}

			try {
				plainText = (String) encryptedMsgObj.getObject(cipher);
			} catch (Exception e) {
				System.out.println("Error extracting line from cipher.");
				System.exit(1);
			}
			return plainText;
		}
	}
}
