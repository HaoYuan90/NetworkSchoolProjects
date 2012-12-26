import java.io.*;
import java.net.*;

class server {
	
	public static void main(String argv[]) throws Exception
	{ 
		//server program should not have and ignores any input parameters
		String n_msg;
		String clientSentence;
		String reversedSentence;
		
        //create socket and print negotiation port
		ServerSocket n_socket = new ServerSocket(0);
		System.out.println("Negotiation port is "+n_socket.getLocalPort());
		
		while(true) {
			Socket connectionSocket = n_socket.accept();
            //create IO streams
			BufferedReader inFromClient = 
				new BufferedReader(new InputStreamReader(connectionSocket.getInputStream()));
			DataOutputStream  outToClient =
				new DataOutputStream(connectionSocket.getOutputStream());
			//receive the negotiation message sent by client
			n_msg = inFromClient.readLine();
			
            //create UDP socket
            DatagramSocket r_socket;
            try{
                r_socket = new DatagramSocket();
            }
            catch(Exception e){
                System.out.println("Failed to create UDP socket.");
                return;
            }
            //send to client the UDP port number
			outToClient.writeBytes(""+r_socket.getLocalPort()+"\n");
			
            //longest msg that can be received is 1024 bytes
			byte[] receivedBuffer = new byte[1024];
			DatagramPacket receivedPacket = new DatagramPacket(receivedBuffer, receivedBuffer.length);
			r_socket.receive(receivedPacket);
			//send the reversed string back to the client
			r_socket.send(toDatagramPacket(reverseString(extractStringFromPacket(receivedPacket)),receivedPacket.getAddress(),receivedPacket.getPort()));
            r_socket.close();
		}
	} 
	
	private static String reverseString(String input)
	{
        //reverse the input string
		return new StringBuffer(input).reverse().toString();
	}
	
	private static DatagramPacket toDatagramPacket (String input, InetAddress address, int portNumber)
	{
		//Converts string to a proper datagrampacket for sending.
		byte[] buffer = input.getBytes();
		return new DatagramPacket(buffer,buffer.length,address,portNumber);
	}
	
	private static String extractStringFromPacket (DatagramPacket p)
	{
        //extract string from a datagram packet
		return new String(p.getData());
	}
}
