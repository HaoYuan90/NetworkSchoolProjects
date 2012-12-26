import java.io.*;
import java.net.*;
import java.util.*;

class sender {

	private static final int seqNumMod = 32;
	private static final int packetStrLen = 500;
	private static final int socketTimeout = 200;
	private static final int ackTimeout = 500;
	private static final int windowSize = 10;

	public static void main(String argv[]) throws Exception
	{
		String hostName;
		int emuRPN;
		int senderRPN;
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
			senderRPN = Integer.parseInt(argv[2]);
		}catch(Exception e){
			System.out.println("Invalid port number.");
			return;
		}
		fileName = argv[3];

		Vector<packet> packets = constructPackets(fileName);
		InetAddress hostAddress = InetAddress.getByName(hostName);
		//create UDP sockets
		DatagramSocket senderS;
		try{
			senderS = new DatagramSocket();
		}
		catch(Exception e){
			System.out.println("Failed to create sending UDP socket.");
			return;
		}
		DatagramSocket senderR;
		try{
			senderR = new DatagramSocket(senderRPN);
		}catch(Exception e){
			System.out.println("Failed to create receiving UDP socket.");
			return;
		}
		//prevent the listening socket from blocking the thread for too long
		senderR.setSoTimeout(socketTimeout);

		//initialise loggers
		PrintWriter logSeqNum = new PrintWriter("SeqNum.log");
		PrintWriter logAck = new PrintWriter("Ack.log");

		//initialise counters
		IntBox sentCounter = new IntBox(-1);
		IntBox timerRunning = new IntBox(0);
		boolean end = false;
		//initialise timer and task
		Timer timer = new Timer();
		ackTimeoutTask task = new ackTimeoutTask ();
		//initialise datagram packet
		byte[] ackBuffer = new byte[512];
		DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);

		while(!end){
			//send packets while there is still space in the window
			while(sentCounter.value<windowSize-1 && sentCounter.value<packets.size()-1){
				sentCounter.value ++;
				packet toSend = packets.get(sentCounter.value);
				senderS.send(toDatagramPacket(toSend, hostAddress, emuRPN));
				logSeqNum.println(""+toSend.getSeqNum());
			}

			//start timer
			if(timerRunning.value == 0){
				task = new ackTimeoutTask (sentCounter,packets,senderS,hostAddress,emuRPN,timerRunning,logSeqNum);
				timer.schedule(task,ackTimeout);
				timerRunning.value = 1;
			}

			//wait for ack from receiver
			try{
				senderR.receive(ackPacket);
				packet ack = packet.parseUDPdata(ackPacket.getData());
				if(ack.getType() == 0){
					//log the acknowlegement
					logAck.println(""+ack.getSeqNum());
					int removeCounter = -1;
					for(int i=0;i<windowSize&&i<packets.size();i++){
						//there is a packet within range matching the seqNum, means this ack is valid
						//if an ack gets delayed by a whole cycle before reaching the sender, the program will malfunction
						//e.g. ack for package 0 is delayed but ack for package 1 is received so sender goes on sending
						//when window include package 0 of next page, ack for previous package 0 arrived
						if(packets.get(i).getSeqNum() == ack.getSeqNum()){
							removeCounter = i;
							task.cancel();
							task = new ackTimeoutTask (sentCounter,packets,senderS,hostAddress,emuRPN,timerRunning,logSeqNum);
							timer.schedule(task,ackTimeout);
							timerRunning.value = 1;
						}
					}
					synchronized(packets){
						for(int i=0;i<=removeCounter;i++){
							packets.remove(0);
							sentCounter.value--;
						}
					}
					//all packets are ackowledged by the receiver
					if(packets.size() == 0){
						task.cancel();
						timer.cancel();
						timerRunning.value = 0;
						end = true;
					}
				}
			}catch(SocketTimeoutException e){
				//do nothing
			}
		}

		//send EOT acknowledegement
		packet toSend = packet.createEOT(sentCounter.value+1);
		senderS.send(toDatagramPacket(toSend, hostAddress, emuRPN));
		//received EOT response
		while(true){
			senderR.receive(ackPacket);
			packet ack = packet.parseUDPdata(ackPacket.getData());
			if(ack.getType() == 2){
				//EOT response received, sender terminate
				break;
			}
		}

		logSeqNum.close();
		logAck.close();
		senderS.close();
		senderR.close();
	}

	public static DatagramPacket toDatagramPacket (packet pkt, InetAddress address, int portNumber)
	{
		//Converts packet to a proper datagrampacket for sending.
		byte[] buffer = pkt.getUDPdata();
		return new DatagramPacket(buffer,buffer.length,address,portNumber);
	}

	private static Vector<packet> constructPackets (String fileName) throws Exception
	{
		//Read from file and put the content into packets
		int packetCount = 0;
		Vector<packet> packets = new Vector<packet>();
		File file = new File(fileName);
		Scanner fileReader;
		try{
			fileReader = new Scanner(file);
		} catch (FileNotFoundException e){
			System.out.println("Cannot find file.");
			return new Vector<packet>();
		}
		String data = new String();
		//read entire file to a string
		while (fileReader.hasNextLine()){
			data += fileReader.nextLine()+"\n";
		}
		//break the string into appropriate substring and create packets
		while (data.length()>packetStrLen){
			String tempS = data.substring(0,packetStrLen);
			data = data.substring(packetStrLen);
			packet tempP = packet.createPacket(packetCount,tempS);
			packets.add(tempP);
			packetCount ++;
		}
		packet temp = packet.createPacket(packetCount,data);
		packets.add(temp);
		packetCount ++;

		fileReader.close();
		return packets;
	}
}

class ackTimeoutTask extends TimerTask {

	private static final int seqNumMod = 32;

	IntBox sentCounter;
	Vector<packet> packets;
	DatagramSocket senderS;
	InetAddress hostAddress;
	int emuRPN;
	IntBox timerRunning;
	PrintWriter logSeqNum;

	public ackTimeoutTask (){
	}

	public ackTimeoutTask (IntBox sentCounter, Vector<packet> packets, DatagramSocket senderS, InetAddress hostAddress, int emuRPN, IntBox timerRunning, PrintWriter logSeqNum){
		this.sentCounter = sentCounter;
		this.packets = packets;
		this.senderS = senderS;
		this.hostAddress = hostAddress;
		this.emuRPN = emuRPN;
		this.timerRunning = timerRunning;
		this.logSeqNum = logSeqNum;
	}

	public void run() {
		//resend all packets that are already sent in the window
		synchronized (packets){
			for(int i=0;i<=sentCounter.value;i++){
				packet toSend = packets.get(i);
				try{
					senderS.send(sender.toDatagramPacket(toSend, hostAddress, emuRPN));
					logSeqNum.println(""+toSend.getSeqNum());
				} catch (Exception e){
					//do nothing
				}
			}
		}
		timerRunning.value = 0;
	}
}

//this class in used to box certain values in an object
//so that TimerTask could be aware of changes made in main thread
class IntBox {
	public int value;

	public IntBox(int startValue) {
		this.value = startValue;
	}
}
