import java.io.*;
import java.net.*;
import java.util.*;

class receiver {

	private static final int seqNumMod = 32;

	public static void main(String argv[]) throws Exception
	{ 
		String hostName;
		int emuRPN;
		int receiverRPN;
		String fileName;

		// make sure number of parameters is correct
		if(argv.length!=4){
			System.out.println("Command should take in 4 parameters.");
			System.out.println("Please check README for more information.");
			return;
		}

		//handle parameters
		hostName = argv[0];
		// make sure port number can be parsed to integer
		try{
			emuRPN = Integer.parseInt(argv[1]);
			receiverRPN = Integer.parseInt(argv[2]);
		}catch(Exception e){
			System.out.println("Invalid port number.");
			return;
		}
		fileName = argv[3];

		//create sockets
		DatagramSocket receiverS;
		try{
			receiverS = new DatagramSocket();
		}
		catch(Exception e){
			System.out.println("Failed to create sending UDP socket.");
			return;
		}
		DatagramSocket receiverR;
		try{
			receiverR = new DatagramSocket(receiverRPN);
		}catch(Exception e){
			System.out.println("Failed to create receiving UDP socket.");
			return;
		}
		//create file and logger
		FileWriter fileWritter = new FileWriter(fileName);
		PrintWriter logArrival = new PrintWriter("Arrival.log");

		int expectedSeq = 0;
		boolean firstMismatch = true;
		byte[] dataBuffer = new byte[512];
		DatagramPacket receivedPacket = new DatagramPacket(dataBuffer, dataBuffer.length);
		InetAddress hostAddress = InetAddress.getByName(hostName);

		while(true){
			receiverR.receive(receivedPacket);
			packet dataPacket = packet.parseUDPdata(receivedPacket.getData());

			packet toSend;
			//terminate loop if EOT is received
			if(dataPacket.getType()==2){
				break;
			}
			else if(dataPacket.getType()==1){
				logArrival.println(""+dataPacket.getSeqNum());
				if(dataPacket.getSeqNum() == expectedSeq){
					if(dataPacket.getType()==2){
						break;
					}
					else {
						firstMismatch = false;
						//write to file and send ack for this packet
						appendStringToFile(dataPacket.getData(),fileName);
						toSend = packet.createACK(expectedSeq);
						expectedSeq = (expectedSeq+1)%seqNumMod;
					}
				}
				else{
					//discard unwanted data packets and resend previous ack
					int temp = expectedSeq-1;
					temp = (temp>=0)?temp:seqNumMod-1;
					toSend = packet.createACK(temp);
				}
				//if the first packet is not received, no acknowlegement will be sent.
				if(!firstMismatch)
					receiverS.send(toDatagramPacket(toSend, hostAddress, emuRPN));
			}
		}
		//replay to EOT with a EOT ack
		packet toSend = packet.createEOT(expectedSeq);
		receiverS.send(toDatagramPacket(toSend, hostAddress, emuRPN));

		logArrival.close();
		receiverS.close();
		receiverR.close();
	}

	private static DatagramPacket toDatagramPacket (packet pkt, InetAddress address, int portNumber)
	{
		//Converts packet to a proper datagrampacket for sending.
		byte[] buffer = pkt.getUDPdata();
		return new DatagramPacket(buffer,buffer.length,address,portNumber);
	}

	private static void appendStringToFile (byte[] data, String fileName) throws Exception
	{
		String input = new String(data);
		try{
			FileWriter fileWriter = new FileWriter(fileName, true);
			BufferedWriter buffWriter = new BufferedWriter(fileWriter);
			buffWriter.write(input);
			buffWriter.close();
		} catch (Exception e){
			System.out.println("Error writing to file.");
		}
	}
}
