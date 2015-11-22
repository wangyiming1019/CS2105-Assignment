/********************************************************************************
* FILE: Alice.java
*	(CS2105 Assignment #3 AY 2015/16 - Semester I)
*
* AUTHOR: MunKeat
* Acknowledgement
* 	Skeletal frame adopted from CS2105 Assignment 03
*
* DATE: 02-Nov-2015
*
* PURPOSE: Alice.java performs the following:
* Procedure:
* (1) Alice possesses Bob's key (bob.pub)
* (2) Alice sends Bob session (AES) key generated
* (3) Alice receives messages from Bob (assuming expected number of lines are 10),
*	decrypts and saves them to file (msgs.txt)
********************************************************************************/

import java.io.*;
import java.net.*;
import java.security.*;
import javax.crypto.*;

public class Alice {
	// File to store received and decrypted messages
	public static final String MESSAGE_FILE = "msgs.txt";
	// Number of lines expected from Bob
	public static final Integer LINES_EXPECTED = 10;

	// Socket used to connect to Bob
	private Socket connectionSkt;
	// Send session key to Bob
	private ObjectOutputStream toBob;
	// Read encrypted messages from Bob
	private ObjectInputStream fromBob;
	private Crypto crypto;

	public static void main(String[] args) {
		// Check if the number of command line argument is 2
		if (args.length != 2) {
			System.err.println("Usage: java Alice <BobIP> <BobPort>");
			System.exit(1);
		}

		String IPAddress = args[0];
		Integer port = Integer.parseInt(args[1]);

		Alice alice = new Alice(IPAddress, port);
		alice.sendSessionKey();
		alice.receiveMessages();
		System.out.println("Termination of Alice/Bob messaging...");
	}

	/********************************************************************************
	 * CONSTRUCTOR: Alice.java
	 ********************************************************************************/
	public Alice(String IPAddress, int port) {
		// Connect to Bob
		try {
			connectionSkt = new Socket(IPAddress, port);
		} catch (IOException e) {
			System.out.println("Error: Unable to constructing a socket for Alice based on parameter provided.");
			e.printStackTrace();
			System.exit(1);
		}
		// Get input/output stream to receive/send object
		try {
			toBob = new ObjectOutputStream(this.connectionSkt.getOutputStream());
			fromBob = new ObjectInputStream(this.connectionSkt.getInputStream());
		} catch (IOException e) {
			System.out.println("Error: Cannot get input/output streams");
			e.printStackTrace();
			System.exit(1);
		}
		crypto = new Crypto();
	}

	/********************************************************************************
	 * METHODS: Alice.java
	 ********************************************************************************/
	// Send session key to Bob
	public void sendSessionKey() {
		if (crypto != null) {
			try {
				toBob.writeObject(crypto.getSessionKey());
				toBob.flush();
				System.out.println("Sealed session key sent successfully to Bob.");
			} catch (IOException e) {
				System.out.println("Unable to send sealed session key to Bob.");
				e.printStackTrace();
				System.exit(1);
			}
		} else {
			System.out.println("Crypto is null!");
		}
	}

	// Receive messages one by one from Bob, decrypt and write to file
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
				SealedObject encryptedBob = (SealedObject) fromBob.readObject();
				String decryptedLine = crypto.decryptMsg(encryptedBob);
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
		// File that contains Bob' public key
		public static final String PUBLIC_KEY_FILE = "bob.pub";

		// Bob's public key, to be read from file
		private PublicKey pubKey;
		// Alice generates a new session key for each communication session
		private SecretKey sessionKey;

		/********************************************************************************
		 * CONSTRUCTOR: Crypto.java
		 ********************************************************************************/
		public Crypto() {
			// Read Bob's public key from file
			File privKeyFile = new File(PUBLIC_KEY_FILE);
			if (privKeyFile.exists() && !privKeyFile.isDirectory()) {
				try {
					ObjectInputStream ois = new ObjectInputStream(new FileInputStream(PUBLIC_KEY_FILE));
					this.pubKey = (PublicKey) ois.readObject();
					ois.close();
				} catch (IOException oie) {
					System.out.println("Error reading public key from file");
					System.exit(1);
				} catch (ClassNotFoundException cnfe) {
					System.out.println("Cannot typecast to class PublicKey");
					System.exit(1);
				}
				System.out.println("Public key successfully read from file " + PUBLIC_KEY_FILE);
			} else {
				System.out.println("Alice cannot find RSA public key.");
				System.exit(1);
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
				//Alice must use the same RSA key/transformation as Bob specified
				cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
				//Initialise cipher with Bob's public key
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
				// Alice and Bob use the same AES key/transformation
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
