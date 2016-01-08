import java.net.*;
import java.io.*;
import java.util.*;

public class WebProxy {
	/** Port for the proxy */
	private static int port;

	/** Socket for client connections */
	private static ServerSocket socket;

	public static void main(String args[]) {
		/** Read command line arguments and start proxy */

		/** Read port number as command-line argument **/

		/** Create a server socket, bind it to a port and start listening **/
		socket =

		/** Main loop. Listen for incoming connections **/
		Socket client = null;

		while (true) {
			try {
				client = socket.accept();
				System.out.println("'Received a connection from: " + client);

				/** Read client's HTTP request **/

				String firstline =
				String[] tmp = firstLine.split(" ");
				String method = tmp[0];
				String URI = tmp[1];
				String version = tmp[2];

			}
			catch (IOException e) {
				System.out.println("Error reading request from client: " + e);
				/* Definitely cannot continue, so skip to next
				 * iteration of while loop. */
				continue;
			}

			/** Check cache if file exists **/
			File f = new File(URI);
			if (f.exists())
			{
				/** Read the file **/
				byte[] fileArray;
				fileArray = Files.readAllBytes(file);

				/** generate appropriate respond headers and send the file contents **/

			}
			else {
				try {
					/** connect to server and relay client's request **/
					Socket server = new Socket(

					/** Get response from server **/

					server.close();
					/** Cache the contents as appropriate **/

					/** Send respose to client **/
					client.close();
				}
				catch (IOException e) {

				}
			}

		}

	}

}
