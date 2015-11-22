/********************************************************************************
* FILE: FileReceiver.java
*	(CS2105 Assignment #2 AY 2015/16 - Semester I)
*
* AUTHOR: MunKeat
* Acknowledgement
* 	Packet_Receiver Class: Adopted and modified from https://github.com/9gix/cs2105/
*
* DATE: 16-Oct-2015
*
* PURPOSE: FileReceiver.java will receive all packets from UnreliNET, a simulation of an
* unreliable channel which may randomly discarding packets, corrupting packets and
* even reordering the packets sent from FileSender.java.
* (In other words, FileSender.java & FileReceiver.java will be able to send and receive
* packets even in the presence of packets being discarded, packet corruption, and
* packet reordering.)
********************************************************************************/

import java.io.File;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

public class FileReceiver {
	public final static CRC32 checksumGenerator = new CRC32();
	public final static boolean DEBUG = false;

	public static class Packet_Receiver {
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
		// Check that port number is provided for in argument
		if (args.length != 1) {
			System.err.println("Usage: FileReceiver <port>");
			System.exit(-1);
		}
		Integer port = Integer.parseInt(args[0]);
		receiveAndAck(port);
	}

	public static void receiveAndAck(int port) throws Exception {
		DatagramSocket socket = new DatagramSocket(port);
		long expectedFileLength = 0;
		int sequence = 0;

		/********************************************************************************
		 * (1) Create new file based on first datagram received
		 ********************************************************************************/
		SocketAddress sa = null;
		String fileName = null;

		byte[] packetData = new byte[Packet_Receiver.PACKET_BUFFER_SIZE];
		ByteBuffer byteBuffer = ByteBuffer.wrap(packetData);
		DatagramPacket packet;

		byte[] ACKdata = new byte[Packet_Receiver.PACKET_HEADER_SIZE];
		ByteBuffer ACKByteBuffer = ByteBuffer.wrap(ACKdata);
		DatagramPacket ACKPacket;

		if(DEBUG) { System.out.println("(1) Waiting for first packet to be received...");}

		while(fileName == null) {
			packet = new DatagramPacket(packetData, packetData.length);
			packet.setLength(packetData.length);
			socket.receive(packet);

			if(Packet_Receiver.isUncorrupted(byteBuffer) && byteBuffer.getInt(Packet_Receiver.SEQ_BYTE_OFFSET) == sequence) {
				byteBuffer.clear();
				ACKByteBuffer.clear();

				expectedFileLength = byteBuffer.getLong(Packet_Receiver.DATA_BYTE_OFFSET);
				if(DEBUG) { System.out.println("Expected File length: " + expectedFileLength);}
				int stringLength = byteBuffer.getInt(Packet_Receiver.DATA_BYTE_OFFSET + 8);
				if(DEBUG) { System.out.println("File name's length: " + stringLength);}
				fileName = new String(packetData, Packet_Receiver.DATA_BYTE_OFFSET + 12, stringLength, "UTF-8");

				//Send ACK
				ACKByteBuffer.clear();
				ACKByteBuffer.putInt(Packet_Receiver.SEQ_BYTE_OFFSET, 0);
				ACKByteBuffer.put(Packet_Receiver.FLAG_BYTE_OFFSET, Packet_Receiver.ACK_MASK);
				ACKByteBuffer.putInt(Packet_Receiver.CHECKSUM_BYTE_OFFSET, Packet_Receiver.generateChecksum(ACKByteBuffer));
				if(DEBUG) {System.out.println("ACK packet sent: " + 0);}
				ACKPacket = new DatagramPacket(ACKdata, 0, ACKdata.length, packet.getSocketAddress());
				socket.send(ACKPacket);
			}
		}

		//Create file
		File file = new File(fileName);
		if(!file.createNewFile() && DEBUG) { System.out.println("File not created successfully!");}
		if(DEBUG) { System.out.println("File created: " + fileName);}
		FileOutputStream fos = new FileOutputStream(file);

		sequence++;

		/********************************************************************************
		 * (2) Receive data containing file content, and send Ack
		 ********************************************************************************/
		if(DEBUG) {System.out.println();}
		if(DEBUG) {System.out.println("(2) Awaiting file to be sent...");}

		while (expectedFileLength > 0) {

			byteBuffer.clear();
			ACKByteBuffer.clear();

			packet = new DatagramPacket(packetData, packetData.length);
			packet.setLength(packetData.length);
			socket.receive(packet);

			int trailingSequence = (int) (((long)sequence - 1 + Integer.MAX_VALUE) % Integer.MAX_VALUE);

			int obtainedSequence = byteBuffer.getInt(Packet_Receiver.SEQ_BYTE_OFFSET);
			boolean isUncorrupted = Packet_Receiver.isUncorrupted(byteBuffer);

			if(DEBUG) {System.out.println();}
			if(DEBUG) {System.out.println("====================================================");}
			//if(DEBUG) {System.out.println("FileReceiver >> Obtained Packet " + obtainedSequence + ": " + Arrays.toString(packetData));}
			if(DEBUG) {System.out.println("FileReceiver >> Obtained Packet " + obtainedSequence);}
			if(DEBUG) {System.out.println("====================================================");}
			if(DEBUG) {System.out.println();}

			if(isUncorrupted && (obtainedSequence == sequence || obtainedSequence == trailingSequence) ){
				//(a) Process header of Datagram Packet
				int ackNumber 	= byteBuffer.getInt(Packet_Receiver.SEQ_BYTE_OFFSET);
				if(DEBUG) {System.out.println("Packet " + ackNumber + " received.");}
				byte flag 		= byteBuffer.get(Packet_Receiver.FLAG_BYTE_OFFSET);

				//(b) Send ACK
				if(DEBUG) {System.out.println("Sending ACK. " + ackNumber);}
				ACKByteBuffer.clear();
				ACKByteBuffer.putInt(Packet_Receiver.SEQ_BYTE_OFFSET, ackNumber);
				ACKByteBuffer.put(Packet_Receiver.FLAG_BYTE_OFFSET, Packet_Receiver.ACK_MASK);
				ACKByteBuffer.putInt(Packet_Receiver.CHECKSUM_BYTE_OFFSET, Packet_Receiver.generateChecksum(ACKByteBuffer));
				if(DEBUG) {System.out.println("ACK packet sent: " + ackNumber);}
				ACKPacket = new DatagramPacket(ACKdata, 0, ACKdata.length, packet.getSocketAddress());

				socket.send(ACKPacket);

				if(DEBUG) {System.out.println("Checksum: " + Packet_Receiver.generateChecksum(ACKByteBuffer));}

				if(DEBUG) {System.out.println();}
				if(DEBUG) {System.out.println("====================================================");}
				//if(DEBUG) {System.out.println("FileReceiver >> Sent ACK Packet " + obtainedSequence + ": " + Arrays.toString(ACKdata));}
				if(DEBUG) {System.out.println("FileReceiver >> Sent ACK Packet " + obtainedSequence);}
				if(DEBUG) {System.out.println("====================================================");}
				if(DEBUG) {System.out.println();}

				if(obtainedSequence == sequence) {
					if(DEBUG) {System.out.println("Yes, packet received as expected... Now writing to file...");}
					if(flag == Packet_Receiver.SYN_MASK){
						//byteBuffer.get(packetData, Packet_Receiver.DATA_BYTE_OFFSET, Packet_Receiver.DATA_BYTE_LENGTH);
						expectedFileLength -= Packet_Receiver.DATA_BYTE_LENGTH;

						fos.write(packetData, Packet_Receiver.DATA_BYTE_OFFSET, Packet_Receiver.DATA_BYTE_LENGTH);
						sequence = (sequence + 1) % Integer.MAX_VALUE;
					} else if (flag == Packet_Receiver.FIN_MASK) {
						int finalPacketLength = (int) expectedFileLength;
						//byteBuffer.get(packetData, Packet_Receiver.DATA_BYTE_OFFSET, finalPacketLength);
						expectedFileLength = 0;

						fos.write(packetData, Packet_Receiver.DATA_BYTE_OFFSET, finalPacketLength);
						fos.flush();
						sequence = (sequence + 1) % Integer.MAX_VALUE;
						sa = packet.getSocketAddress();
					}
				}
			}
		}

		fos.close();

		/********************************************************************************
		 * (3) Termination
		 ********************************************************************************/
		if(DEBUG) {System.out.println("Attempting Termination...");}

		byteBuffer.clear();
		ACKByteBuffer.clear();

		ACKByteBuffer.putInt(Packet_Receiver.SEQ_BYTE_OFFSET, 0);
		ACKByteBuffer.put(Packet_Receiver.FLAG_BYTE_OFFSET, Packet_Receiver.END_MASK);
		ACKByteBuffer.putInt(Packet_Receiver.CHECKSUM_BYTE_OFFSET, Packet_Receiver.generateChecksum(ACKByteBuffer));
		ACKPacket = new DatagramPacket(ACKdata, 0, ACKdata.length, sa);

		while(true) {
			socket.send(ACKPacket);
		}
	}
}
