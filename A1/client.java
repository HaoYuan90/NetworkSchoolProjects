import java.io.*;
import java.net.*;

class client {
    
    //the number sent by client to start negotiation
    private static final int n_id = 10;

	public static void main(String argv[]) throws Exception
	{
		// client takes in 3 parameters hostname_portnumber_msg
		// symbol "_" denotes a single space
		// msg and hostname are to be enclosed by"" if it contains spaces
		String hostName;
		int n_portNumber;
		String msg;
		int r_portNumber;
		
		String serverResponse;
		
		// make sure number of parameters is correct
		if(argv.length!=3){
			System.out.println("Command should take in 3 parameters.");
            System.out.println("Please check README for more information");
			return;
		}
		
        //handle parameters
		hostName = argv[0];
		// make sure port number can be parsed to integer
		try{
			n_portNumber = Integer.parseInt(argv[1]);
		}
		catch(Exception e){
			System.out.println("Invalid port number.");
			return;
		}
		msg = argv[2];
		
        //initialize socket and connect to server
		Socket n_socket;
		try{
			n_socket = new Socket(hostName, n_portNumber);
		}
		catch(Exception e){
			System.out.println("Failed to negotiate with server.");
			return;
		}
        
		//create IO streams
		DataOutputStream outToServer =
			new DataOutputStream(n_socket.getOutputStream());
		BufferedReader inFromServer =
			new BufferedReader(new InputStreamReader(n_socket.getInputStream()));
        //send negotiation msg
		outToServer.writeBytes("" + n_id + '\n');
		//receive r_port from server
		r_portNumber = Integer.parseInt(inFromServer.readLine());
        n_socket.close();
		
		InetAddress hostAddress = InetAddress.getByName(hostName);
        //create UDP socket
        DatagramSocket r_socket;
        try{
            r_socket = new DatagramSocket();
        }
        catch(Exception e){
            System.out.println("Failed to create UDP socket.");
			return;
        }
        //send message to server
		r_socket.send(toDatagramPacket(msg, hostAddress, r_portNumber));
        //longest msg allowed is 1024 bytes
		byte[] receivedBuffer = new byte[1024];
		DatagramPacket receivedPacket = new DatagramPacket(receivedBuffer, receivedBuffer.length);
        //receive message to server
		r_socket.receive(receivedPacket);
		System.out.println("*** SERVER RESPONSE ***\n" + extractStringFromPacket(receivedPacket));
		r_socket.close();
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
