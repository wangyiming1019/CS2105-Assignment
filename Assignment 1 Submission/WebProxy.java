/********************************************************************************
* FILE: WebProxy.java
*	(CS2105 Assignment #1 AY 2015/16 - Semester I)
*
* AUTHOR: MunKeat
*
* DATE: 20-Sep-2015
*
* PURPOSE: Webproxy.java is able to:
* (a) Handle small web objects like a small file or image
* (b) Handle a complex web page with multiple objects, e.g., www.comp.nus.edu.sg
* (c) Handle files up to 1GB
* (d) Handle erroneous requests such as a “404” response, and also return a “502”
* response if it cannot reach the web server
* (e) Handle the POST method in addition to GET method, and will correctly
* include the request body sent in the POST-request.
* (f) Perform "Advanced caching", as stipluated in the assignment requirement.
* That is, WebProxy.java will cached the oject on the first request (assuming
* object exist), and upon subseqent request, send a request to the origin server
* with a header If-Modified-Since. (In other words, Conditional Request.)
* (g) Perform text-censorship, by replacing words in web object that appeared in
* censor.txt with three dashes - "---". Assumes that text is based on UTF-8.
********************************************************************************/

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

public class WebProxy extends Thread {
	private final static int BUFFER_SIZE = 32768;
	private final static int DEFAULT_PROXY = 1200;
	private final static String CENSOR_LIST = "censor.txt";
	private final static boolean DEBUG = false;

	private static final String OUTPUT_END_OF_HEADERS = "\r\n\r\n";
	private static final String OUTPUT_END_OF_LINE = "\r\n";

	// To cached objects as they are requested
	private static final HashMap<String, Tuple> cache = new HashMap<String, Tuple>();
	// List of censored words
	private static final ArrayList<String> censoredWords = new ArrayList<String>();

	private byte[] readArray = new byte[BUFFER_SIZE];
	private ServerSocket serverSocket;
	private String tempLine;
	private int tempInt;
	private int count;

	// Initialize the censored words
	static {
		// Check if file exist
		URL path = ClassLoader.getSystemResource(CENSOR_LIST);
		if (path != null) {
			try {
				String temp;
				File f = new File(path.toURI());
				BufferedReader reader = new BufferedReader(new FileReader(f));

				while ((temp = reader.readLine()) != null) {
					censoredWords.add(temp.toLowerCase());
				}
				reader.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public WebProxy(ServerSocket serverSocket) {
		this.serverSocket = serverSocket;
	}

	public static class Tuple {
		private String date;
		private File retrieved;

		public Tuple(String date, File retrieved) {
			this.date = date;
			this.retrieved = retrieved;
		}

		public String getDate() {
			return this.date;
		}

		public File getCache() {
			return this.retrieved;
		}
	}

	// For connection with each client socket
	public void run() {
		Socket client = null;
		BufferedInputStream streamFromClient = null;
		BufferedOutputStream streamToClient = null;

		try {
			/****************************************************************************************************
			 *
			 * (a) Retrieve request from client
			 *
			 ****************************************************************************************************/
			// Initialize client socket and its I/O Stream
			client = serverSocket.accept();
			streamFromClient = new BufferedInputStream(client.getInputStream());
			streamToClient = new BufferedOutputStream(client.getOutputStream());

			if (DEBUG) {System.out.println("Receiving connection from: " + client.toString());}

			// Get client's request
			final int sessionNum = new Random().nextInt();
			File clientRequest = File.createTempFile("Client-HTTP-Request" + sessionNum, ".txt");
			clientRequest.deleteOnExit();

			FileOutputStream writer = new FileOutputStream(clientRequest, false);
			while (((streamFromClient.available() != 0)	&& (tempInt = streamFromClient.read(readArray, 0, readArray.length)) >= 0)) {
				writer.write(readArray, 0, tempInt);
				writer.flush();
			}

			writer.close();

			count = 1;
			BufferedReader fileReader = new BufferedReader(new FileReader(clientRequest));
			tempLine = fileReader.readLine();

			String[] tempArray = tempLine.split(" ");
			String method = tempArray[0];
			String URI = tempArray[1];

			if(DEBUG) {
			System.out.println("Method:	" + method);
			System.out.println("URI:	" + URI);
			}

			/****************************************************************************************************
			 * DEBUGGING: Print client request stored
			 ****************************************************************************************************/
			if(DEBUG){
			System.out.println("");
			System.out.println("========== DEBUGGING: PRINT CLIENT REQUEST STORED ==========");
			}
			//Reference: http://www.w3.org/Protocols/HTTP/1.0/spec.html#Request
			boolean reachedContentBody = false;

			BufferedWriter HTTPRequestBodyWriter = null;
			File HTTPRequestBody = null;
			String POSTContentType = null;

			if(method.equals("POST")) {
				HTTPRequestBody = File.createTempFile("Client-HTTP-Request-Body-" + sessionNum, ".txt");
				HTTPRequestBody.deleteOnExit();
				HTTPRequestBodyWriter = new BufferedWriter(new FileWriter(HTTPRequestBody));
			}

			if(DEBUG) {System.out.println("Line " + count + ": " + tempLine);}
			while ((tempLine = fileReader.readLine()) != null) {
				if (method.equals("POST")) {

					if(tempLine.contains("Content-Type:")) {
						POSTContentType = tempLine.substring(tempLine.indexOf(": ") + 1, tempLine.length());
						if(DEBUG) {System.out.println("Post Content-Type:" + POSTContentType);}
					}

					if (reachedContentBody) {
						HTTPRequestBodyWriter.write(tempLine);
						HTTPRequestBodyWriter.flush();
						HTTPRequestBodyWriter.newLine();
					}

					// Trigger if reaching empty line
					if (tempLine.trim().equals("")) {
						reachedContentBody = true;
					}
				}

				if(DEBUG) {System.out.println("Line " + count + ": " + tempLine);}
				count++;
			}
			if(DEBUG){
			System.out.println("========== DEBUGGING: END CLIENT REQUEST STORED ==========");
			System.out.println();
			}

			fileReader.close();
			/****************************************************************************************************
			 *
			 * (b) Retrieve object from server
			 *
			 ****************************************************************************************************/
			// Check if input(s) are correct/exist
			if (method == null || URI == null) {
				if(DEBUG) {System.out.println("Method / URI does not exist. Application will now terminate...");}

				// Cleanup
				streamFromClient.close();
				streamToClient.close();
				client.close();
				return;
			}

			URL url = new URL(URI);
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();

			if(DEBUG){System.out.println("Established connection with: " + url.getHost());}
			connection.setRequestMethod(method);

			//Additional configuration for POST request
			if(method.equals("POST")) {
				connection.setDoOutput(true);
				connection.setRequestProperty("Content-Type", POSTContentType);

				BufferedOutputStream output = new BufferedOutputStream(connection.getOutputStream());
				BufferedInputStream requestReader = new BufferedInputStream(new FileInputStream(HTTPRequestBody));

				transferBufferedStream(requestReader, output);

				requestReader.close();
			}

			if (method.equals("GET") && cache.containsKey(URI)) {
				// Case 1: GET && cached
				if(DEBUG) {System.out.println("Object appears to have been previously requested. Checking cache now...");}

				// -> Retrieving the "Last-Modified" field
				Tuple cachedItem = cache.get(URI);
				String lastModified = cachedItem.getDate();

				if (lastModified != null && !lastModified.equals("")) {
					// -> Append client request with an "If-Modified-Since" field
					connection.setRequestProperty("If-Modified-Since", lastModified);
				}

				// -> Get status and file type
				int statusCode = connection.getResponseCode();
				String contentType = connection.getContentType();
				boolean isText = false;

				if (contentType != null) {
					/****************************************************************************************************
					 * DEBUGGING: What is the Content-Type?
					 ****************************************************************************************************/
					if(DEBUG) {System.out.println("Content Type: " + contentType);}
					isText = contentType.contains("text/");
				}

				if (statusCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
					writeHTTPResponse(statusCode, streamToClient, connection);

					// ->> Case 1a: HTTP 304 - Not Modified
					File cached = cachedItem.getCache();

					if(DEBUG) {System.out.println("File Exist: " + cached.exists());}
					if(DEBUG) {System.out.println("File Size: " + cached.length());}

					FileInputStream input = new FileInputStream(cached);

					while ((tempInt = input.read(readArray, 0, readArray.length)) >= 0) {
						streamToClient.write(readArray, 0, tempInt);
						streamToClient.flush();
					}
					input.close();

					/****************************************************************************************************
					 * DEBUGGING: Status 304 Flag
					 ****************************************************************************************************/
					if(DEBUG) {System.out.println("STATUS 304: HTTP_NOT_MODIFIED - DONE SENDING CACHED ITEM TO CLIENT...");}
					streamToClient.close();
				}

				else {
					// ->> Case 1b: Cache the file (and censor if necessary)
					// -> Get server's reply (FILE)
					File cached = cachedItem.getCache();
					writer = new FileOutputStream(cached, false);

					if (!isText) {
						BufferedInputStream streamFromServer = new BufferedInputStream(connection.getInputStream());

						writeHTTPResponse(statusCode, streamToClient, connection);
						writeHTTPNonTextBody(streamFromServer, streamToClient, writer);

						/****************************************************************************************************
						 * DEBUGGING: Status != 304, object sent to client
						 ****************************************************************************************************/
						if(DEBUG) {System.out.println("STATUS " + statusCode + ": RETRIEVED NON-TEXT, DONE SENDING CACHED ITEM TO CLIENT...");}
					}

					else {
						BufferedInputStream streamFromServer = new BufferedInputStream(connection.getInputStream());

						writeHTTPResponse(statusCode, streamToClient, connection);
						writeHTTPTextBody(streamFromServer, streamToClient, writer);

						/****************************************************************************************************
						 * DEBUGGING
						 ****************************************************************************************************/
						String dated = connection.getHeaderField("Last-Modified");
						cache.put(URI, new Tuple(dated, cached));
						if(DEBUG) {System.out.println("STATUS " + statusCode + ": RETRIEVED TEXT, DONE SENDING CACHED ITEM TO CLIENT...");}
					}
				}
			}

			else {
				// Case 2: Not cached
				if(DEBUG) {System.out.println("Object appears to be newly requested. Checking server now...");}

				// -> Get status and file type
				int statusCode = connection.getResponseCode();
				String contentType = connection.getContentType();
				boolean isText = false;

				if (contentType != null) {
					/****************************************************************************************************
					 * DEBUGGING: What is the Content-Type?
					 ****************************************************************************************************/
					if(DEBUG) {System.out.println("Content Type: " + contentType);}

					isText = contentType.contains("text/");
				}

				if (statusCode == HttpURLConnection.HTTP_NOT_FOUND || statusCode == HttpURLConnection.HTTP_BAD_GATEWAY || statusCode >= 400) {
					// ->> Get: 404 / 502
					// ->> SEND 404 / 502 Status
					if(DEBUG) {System.out.println("Error - Status " + statusCode);}
					// SEND APPROPRIATE MESSAGE
					BufferedInputStream errorStreamFromServer= new BufferedInputStream(connection.getErrorStream());
					writeHTTPResponse(statusCode, streamToClient, connection);
					transferBufferedStream(errorStreamFromServer, streamToClient);

				} else if (method.equals("GET")) {
					// ->> Get method: GET && NOT 404 / 502
					// -> Get server's reply (FILE)
					File cached = File.createTempFile("" + URI.hashCode(), "tmp");
					cached.deleteOnExit();
					writer = new FileOutputStream(cached);


					if (!isText) {
						BufferedInputStream streamFromServer = new BufferedInputStream(connection.getInputStream());

						writeHTTPResponse(statusCode, streamToClient, connection);
						writeHTTPNonTextBody(streamFromServer, streamToClient, writer);
					}

					else {
						BufferedInputStream streamFromServer = new BufferedInputStream(connection.getInputStream());

						writeHTTPResponse(statusCode, streamToClient, connection);
						writeHTTPTextBody(streamFromServer, streamToClient, writer);

					}
					// ->> Cache
					String dated = connection.getHeaderField("Last-Modified");
					cache.put(URI, new Tuple(dated, cached));

					/****************************************************************************************************
					 * DEBUGGING
					 ****************************************************************************************************/
					if(DEBUG) {System.out.println("STATUS " + statusCode + ": RETRIEVED NEW, SENT TO CLIENT... ITEM CACHED");}

				} else if (method.equals("POST")) {
					// ->> Get method: POST && NOT 404 / 502
					// ->> SEND TO CLIENT
					if (!isText) {
						BufferedInputStream streamFromServer = new BufferedInputStream(connection.getInputStream());

						writeHTTPResponse(statusCode, streamToClient, connection);
						transferBufferedStream(streamFromServer, streamToClient);
					}

					else {
						BufferedInputStream streamFromServer = new BufferedInputStream(connection.getInputStream());
						writeHTTPResponse(statusCode, streamToClient, connection);

						File tempFile = File.createTempFile("" + sessionNum, ".tmp");
						FileOutputStream originalWriter = new FileOutputStream(tempFile);

						while ((tempInt = streamFromServer.read(readArray, 0, readArray.length)) >= 0) {
							// Create file
							originalWriter.write(readArray, 0, tempInt);
							originalWriter.flush();
						}

						originalWriter.close();
						censorship(tempFile);

						FileInputStream censored = new FileInputStream(tempFile);

						while ((tempInt = censored.read(readArray, 0, readArray.length)) >= 0) {
							// Write to client
							streamToClient.write(readArray, 0, tempInt);
							streamToClient.flush();
						}

						censored.close();
					}
				}
			}
			client.close();
			connection.disconnect();

			/****************************************************************************************************
			 * DEBUGGING: Demarcate the end of connection
			 ****************************************************************************************************/
			if(DEBUG) {
			System.out.println("Terminating Connection...");
			System.out.println("");
			System.out.println("");
			System.out.println("");
			}

		} catch (Exception e) {
			e.printStackTrace();
			if(streamToClient != null) {
				writeHTTPResponse(502, streamToClient, null);
				if(DEBUG) {System.out.println("Sent HTTP Status 502");}
			}
		}

	}

	private static void censorship(File file) throws Exception {
		//Adopted and modified from: http://stackoverflow.com/questions/3935791/find-and-replace-words-lines-in-a-file
		String content = new String(Files.readAllBytes(file.toPath()) , StandardCharsets.UTF_8);

		for (String s : censoredWords) {
			content = content.replaceAll("(?i)" + s, "---");
		}

		PrintWriter pw = new PrintWriter(file);
		pw.print(content);
		pw.close();
	}

	private static void writeHTTPResponse(int statusCode, BufferedOutputStream serveToServer, HttpURLConnection conn) {
		//Wrap around serveToServer
		PrintWriter serveToClient = new PrintWriter(serveToServer);

		if(statusCode == 304) {
			serveToClient.print("HTTP/1.1 200 OK");
			serveToClient.print(OUTPUT_END_OF_HEADERS);
			serveToClient.flush();

			return;
		}

		String message = null;

		if(conn == null) {
			message = "HTTP/1.0 502 Bad Gateway";
		} else {
			message = (conn.getHeaderField(0));
			if(DEBUG) {System.out.println("MESSAGE: " + message);}
		}

		serveToClient.print(message);
		serveToClient.print(OUTPUT_END_OF_HEADERS);
		serveToClient.flush();

		if(conn == null) {
			serveToClient.close();
		}
	}

	private void writeHTTPTextBody(BufferedInputStream streamFromServer, BufferedOutputStream streamToClient, FileOutputStream cachedWriter) throws Exception {
		final int sessionNum = new Random().nextInt();
		File tempFile = File.createTempFile("" + sessionNum, ".tmp");
		FileOutputStream originalWriter = new FileOutputStream(tempFile);

		while ((tempInt = streamFromServer.read(readArray, 0, readArray.length)) >= 0) {
			// Create file
			originalWriter.write(readArray, 0, tempInt);
			originalWriter.flush();
		}

		originalWriter.close();
		censorship(tempFile);

		FileInputStream censored = new FileInputStream(tempFile);

		while ((tempInt = censored.read(readArray, 0, readArray.length)) >= 0) {
			// Write to cached
			cachedWriter.write(readArray, 0, tempInt);
			cachedWriter.flush();
			// Write to client
			streamToClient.write(readArray, 0, tempInt);
			streamToClient.flush();
		}

		censored.close();
	}

	private void writeHTTPNonTextBody(BufferedInputStream streamFromServer, BufferedOutputStream streamToClient, FileOutputStream cachedWriter) throws IOException{
		while ((tempInt = streamFromServer.read(readArray, 0, readArray.length)) >= 0) {
			// Write to cached
			cachedWriter.write(readArray, 0, tempInt);
			cachedWriter.flush();
			// Write to client
			streamToClient.write(readArray, 0, tempInt);
			streamToClient.flush();
		}
	}

	private void transferBufferedStream(BufferedInputStream input, BufferedOutputStream output) throws IOException {
		while (((input.available() != 0)	&& (tempInt = input.read(readArray, 0, readArray.length)) >= 0)) {
			output.write(readArray, 0, tempInt);
			output.flush();
		}
	}

	public static void main(String[] args) {
		boolean listening = true;
		// Default to DEFAULT PROXY, otherwise use the first argument as proxy number
		int localPort = (args.length == 0) ? DEFAULT_PROXY : Integer.parseInt(args[0]);

		try {
			ServerSocket serverSocket = new ServerSocket(localPort);
			if(DEBUG) {System.out.println("ServerSocket ready...");}
			while (listening) {
				new WebProxy(serverSocket).run();
			}
			serverSocket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
