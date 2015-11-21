/********************************************************************************
* FILE: FileSender.java
*	(CS2105 Assignment #2 AY 2015/16 - Semester I)
*
* AUTHOR: MunKeat
* Acknowledgement
*		Packet_Sender Class: Adopted and modified from https://github.com/9gix/cs2105/
*
* DATE: 16-Oct-2015
*
* PURPOSE: FileSender.java will send all packets to UnreliNET, a simulation of an
* unreliable channel which may randomly discarding packets, corrupting packets and
* even reordering the packets before forwarding those packets to FileReceiver.java.
* (In other words, FileSender.java & FileReceiver.java will be able to send and receive
* packets even in the presence of packets being discarded, packet corruption, and
* packet reordering.)
********************************************************************************/
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

public class FileSender {
	public final static CRC32 checksumGenerator = new CRC32();
	public final static boolean DEBUG = false;

	public static class Packet_Sender {
		// Packet_Sender Header
		public final static int CHECKSUM_BYTE_OFFSET = 0;
		public final static int CHECKSUM_BYTE_LENGTH = 4;
		public final static int SEQ_BYTE_OFFSET = 4;
		public final static int SEQ_BYTE_LENGTH = 4;
		public final static int FLAG_BYTE_OFFSET = 8;
		public final static int FLAG_BYTE_LENGTH = 1;
		// Packet_Sender Body
		public final static int DATA_BYTE_OFFSET = 9;
		public final static int DATA_BYTE_LENGTH = 991;
		// Packet_Sender
		public final static int PACKET_HEADER_SIZE = SEQ_BYTE_LENGTH + CHECKSUM_BYTE_LENGTH + FLAG_BYTE_LENGTH;
		public final static int PACKET_BUFFER_SIZE = SEQ_BYTE_LENGTH + CHECKSUM_BYTE_LENGTH + FLAG_BYTE_LENGTH + DATA_BYTE_LENGTH;
		// Flag Byte Status
		public final static byte SYN_MASK = (byte) 0b00000001;
		public final static byte ACK_MASK = (byte) 0b00000010;
		public final static byte NAK_MASK = (byte) 0b00000100;
		public final static byte FIN_MASK = (byte) 0b00001000;
		public final static byte END_MASK = (byte) 0b11111111;

		/// Methods
		public static int generateChecksum(ByteBuffer buffer) {
			byte[] data = buffer.array();
			checksumGenerator.reset();
			checksumGenerator.update(data, CHECKSUM_BYTE_LENGTH, data.length - CHECKSUM_BYTE_LENGTH);
			return (int) checksumGenerator.getValue();
		}

		public static boolean isUncorrupted(ByteBuffer buffer) {
			if(DEBUG) {System.out.println();}
			if(DEBUG) {System.out.println("Entering isUncorrupted...");}
			int generatedChecksum = generateChecksum(buffer);
			if(DEBUG){System.out.println(">> Generated checksum: " + generatedChecksum);}
			int originalChecksum = buffer.getInt(CHECKSUM_BYTE_OFFSET);
			if(DEBUG){System.out.println(">> Obtained checksum: " + originalChecksum);}
			buffer.rewind();
			return (generatedChecksum == originalChecksum);
		}

		public static boolean isEOF(ByteBuffer buffer) {
			byte originalFlag = buffer.get(FLAG_BYTE_OFFSET);
			buffer.rewind();
			return (originalFlag == FIN_MASK);
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 4) {
			System.err.println("Usage: SimpleUDPSender <host> <port> <file_name> <new_file_name>");
			System.exit(-1);
		}
		// Get parameter
		String host 		= args[0];
		Integer port 		= Integer.parseInt(args[1]);
		String fileName 	= args[2];
		String newFileName 	= args[3];
		// Get address
		InetSocketAddress addr = new InetSocketAddress(host, port);
		DatagramSocket socket = new DatagramSocket();

		socket.setSoTimeout(1);

		run(socket, addr, fileName, newFileName);
	}

	public static void run(DatagramSocket socket, InetSocketAddress address, String fileName, String newFileName) throws IOException {
		int sequence = 0;
		FileInputStream reader = new FileInputStream(fileName);
		boolean continueFlag = true;

		/********************************************************************************
		 * (1) Send new file's name & length to other party
		 ********************************************************************************/
		long fileSize = new File(fileName).length();
		byte[] rawData = newFileName.getBytes("UTF-8");

		byte[] packetData = new byte[Packet_Sender.PACKET_BUFFER_SIZE];
		ByteBuffer byteBuffer = ByteBuffer.wrap(packetData);
		DatagramPacket packet = null;

		byte[] dataRec = new byte[Packet_Sender.PACKET_HEADER_SIZE];
		ByteBuffer byteBufferRec = ByteBuffer.wrap(dataRec);
		DatagramPacket packetRec = null;

		// (a) Prepare Packet for sending
		byteBuffer.putInt(Packet_Sender.SEQ_BYTE_OFFSET, sequence);
		byteBuffer.put(Packet_Sender.FLAG_BYTE_OFFSET, Packet_Sender.SYN_MASK);
		//Put (i) File size, (2) File name's length, & (3) File name
		byteBuffer.putLong(Packet_Sender.DATA_BYTE_OFFSET, fileSize);
		byteBuffer.putInt(Packet_Sender.DATA_BYTE_OFFSET + 8, rawData.length);
		byteBuffer.position(Packet_Sender.DATA_BYTE_OFFSET + 12);
		byteBuffer.put(rawData, 0, rawData.length);
		//Generate checksum
		byteBuffer.putInt(Packet_Sender.CHECKSUM_BYTE_OFFSET, Packet_Sender.generateChecksum(byteBuffer));

		//if(DEBUG) {System.out.println("====================================================");}
		//if(DEBUG) {System.out.println(Arrays.toString(packetData));}
		//if(DEBUG) {System.out.println("====================================================");}

		packet = new DatagramPacket(packetData, packetData.length, address);
		socket.send(packet);

		if (DEBUG) { System.out.println("(1) Checking if other party has established the new file..."); }

		// (b) Wait for ACK of sent packet
		while (continueFlag) {
			byteBufferRec.clear();
			// (c) Receive ACK, ideally. Otherwise, send again
			try {
				packetRec = new DatagramPacket(dataRec, dataRec.length);
				packetRec.setLength(dataRec.length);

				if(DEBUG) {System.out.println("Awaiting ACK receiving...");}
				socket.receive(packetRec);
				if(DEBUG) {System.out.println("Receive successful!");}

				//If receive successfully
				if( Packet_Sender.isUncorrupted(byteBufferRec) && byteBufferRec.getInt(Packet_Sender.SEQ_BYTE_OFFSET) == sequence){
					if (DEBUG) { System.out.println("Other party has successfully established file..."); }
					continueFlag = false;
				} else {
					socket.send(packet);
				}

			} catch (SocketTimeoutException s) {
				if(DEBUG) {System.out.println("Lost ACK/SYN Packet... Much like lost sheeps...");}
				socket.send(packet);
			}
		}

		continueFlag = true;
		sequence++;
		/********************************************************************************
		 * (2) Send file's data to other party
		 ********************************************************************************/
		int bytesRead = 0;

		if(DEBUG) {System.out.println();}
		if(DEBUG) {System.out.println("(2) Sending actual file to other party...");}

		while((bytesRead = reader.read(packetData, Packet_Sender.DATA_BYTE_OFFSET, Packet_Sender.DATA_BYTE_LENGTH)) > 0) {
			byteBuffer.clear();

			//(a) Prepare packet for sending
			byteBuffer.putInt(Packet_Sender.SEQ_BYTE_OFFSET, sequence);
			fileSize -= bytesRead;
			if (fileSize > 0) {
				byteBuffer.put(Packet_Sender.FLAG_BYTE_OFFSET, Packet_Sender.SYN_MASK);
			} else {
				byteBuffer.put(Packet_Sender.FLAG_BYTE_OFFSET, Packet_Sender.FIN_MASK);
			}
			byteBuffer.putInt(Packet_Sender.CHECKSUM_BYTE_OFFSET, Packet_Sender.generateChecksum(byteBuffer));

			if(DEBUG) {System.out.println();}
			if(DEBUG) {System.out.println("====================================================");}
			//if(DEBUG) {System.out.println("FileSender >> Packet " + sequence + ": " + Arrays.toString(packetData));}
			if(DEBUG) {System.out.println("FileSender >> Sending Packet " + sequence);}
			if(DEBUG) {System.out.println("====================================================");}

			if(DEBUG) {System.out.println("Status Report");}
			if(DEBUG) {System.out.println("Checksum: " + Packet_Sender.generateChecksum(byteBuffer));}
			if(DEBUG) {System.out.println();}

			//(b) Sending packet
			packet = new DatagramPacket(packetData, packetData.length, address);
			socket.send(packet);

			while (continueFlag) {
				// (c) Receive ACK, ideally. Otherwise, send again
				try {
					byteBufferRec.clear();
					packetRec = new DatagramPacket(dataRec, dataRec.length);
					packetRec.setLength(dataRec.length);
					socket.receive(packetRec);

					if(DEBUG) {System.out.println("ACK received!");}

					if(DEBUG) {System.out.println();}
					if(DEBUG) {System.out.println("====================================================");}
					//if(DEBUG) {System.out.println("FileSender >> ACK Received" + ": " + Arrays.toString(dataRec));}
					if(DEBUG) {System.out.println("FileSender >> ACK Received");}
					if(DEBUG) {System.out.println("====================================================");}

					if(DEBUG) {System.out.println("Status of ACK...");}
					if(DEBUG) {System.out.println("Uncorrupted: " + Packet_Sender.isUncorrupted(byteBufferRec));}
					if(DEBUG) {System.out.println();}

					//If receive successfully
					if(Packet_Sender.isUncorrupted(byteBufferRec) && byteBufferRec.getInt(Packet_Sender.SEQ_BYTE_OFFSET) == sequence){
						if(DEBUG) {System.out.println("Yes, ACK received as expected... Now moving onto the next block...");}
						if(DEBUG) {System.out.println("ACK " + sequence + " received!");}
						continueFlag = false;
						sequence = (sequence + 1) % Integer.MAX_VALUE;
					} else {
						socket.send(packet);
					}

				} catch (SocketTimeoutException s) {
					if(DEBUG) {System.out.println("Lost ACK/SYN Packet... Much like lost sheeps...");}
					socket.send(packet);
				}
			}
			continueFlag = true;
		}
		reader.close();

		/********************************************************************************
		 * (3) Termination
		 ********************************************************************************/
		while (continueFlag) {
			// (c) Receive END, ideally. Otherwise, send again
			try {
				packetRec = new DatagramPacket(dataRec, dataRec.length);
				packetRec.setLength(dataRec.length);
				socket.receive(packetRec);

				//If receive successfully
				if(Packet_Sender.isUncorrupted(byteBufferRec) && byteBufferRec.get(Packet_Sender.FLAG_BYTE_OFFSET) == Packet_Sender.END_MASK){
					return;
				} else {
					continue;
				}

			} catch (SocketTimeoutException s) {
				if(DEBUG) {System.out.println("Waiting for END Flag...");}
			}
		}
	}
}
