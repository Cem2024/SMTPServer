import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.nio.file.Files;

public class ServerSMTP {

    private static Charset messageCharset = StandardCharsets.US_ASCII;
    private ServerSocketChannel servSocket;
    private Selector mainSelector;
    int dataCounter;
    private String receiver;
    private String sender;
    private byte[] data;
    private String endOfMailData = "\r\n.\r\n";

    private String endOfMailDataEscaped = "\\r\\n.\\r\\n";
    private String bufferContent;
    private String responseForClient = null;
    private int port = 4444;
    private static byte[] connectionCode = null;
    private ByteBuffer readBuffer = ByteBuffer.allocate(8000);
    private static byte[] endMsg = null;

    /**
     * Initializes the response codes for the SMTP server.
     */

    private static void initCodes() {
        connectionCode = "220".getBytes(messageCharset);
        endMsg = " \r\n".getBytes(messageCharset);
    }

    /**
     * Sends a response to the client.
     *
     * @param clientChannel     The channel to the client.
     * @param buffer            The buffer containing the response data.
     * @param responseForClient The response message to be sent.
     * @throws IOException If an I/O error occurs while sending the response.
     */

    private static void sendResponse(SocketChannel clientChannel, ByteBuffer buffer, String responseForClient)
            throws IOException {
        // Clear the buffer to prepare it for writing
        ((Buffer) buffer).clear();

        // Convert the response message to a byte array and put it into the buffer along
        // with the end message
        byte[] response = responseForClient.getBytes(messageCharset);
        buffer.put(response);
        buffer.put(endMsg);

        // Flip the buffer to switch it to read mode and write the buffer data to the
        // client
        ((Buffer) buffer).flip();
        while (buffer.hasRemaining()) {
            clientChannel.write(buffer);
        }

        // Clear the buffer for the next use
        ((Buffer) buffer).clear();
    }


    /**
     * Creates a directory for the receiver and saves the email data to a file.
     *
     * @param sender   The sender's email address.
     * @param receiver The receiver's email address.
     * @param data     The email data.
     * @throws IOException If an I/O error occurs while creating the directory or
     *                     file.
     */

    private static void createDirAndEmail(String sender, String receiver, byte[] data) throws IOException {

        // Message id
        Random randomizer = new Random();
        int message_id = randomizer.nextInt(10000);

        // Creating Folder
        String fileName = sender + "_" + message_id + ".txt";
        Path folder = Paths.get(receiver);
        if (!Files.exists(folder)) {
            Files.createDirectories(folder);
        }

        // Creating File with Data
        Path file = folder.resolve(fileName);
        Files.write(file, data);
    }

    /**
     * Constructor for the SMTP server.
     * Constructor initializes server with opening a selector
     * and configuring the server socket channel and accept incoming requests.
     * Channel is configured as non-blocking and is registered with the selector to
     * accept.
     *
     * @throws IOException If one I/O error occurs while opening the selector or
     *                     server socket channel.
     */

    // Initial Start for Server
    public ServerSMTP() throws IOException {
        // Selector declaration
        mainSelector = Selector.open();
        dataCounter = 0;

        // ServerSocket Channel with configs
        servSocket = ServerSocketChannel.open();
        servSocket.bind(new InetSocketAddress(port)); // Random port
        servSocket.configureBlocking(false); // non blocking

        servSocket.register(mainSelector, SelectionKey.OP_ACCEPT);

    }

    /**
     * Main Loop. Handles incoming requests and sending response.
     *
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the thread is interrupted while waiting for
     *                              events.
     */

    public void workingLoop() throws IOException, InterruptedException {
        while (true) {

            // Waiting for Events
            if (mainSelector.select() == 0) {
                continue;
            }
            Set<SelectionKey> selectedKeys = mainSelector.selectedKeys(); // List of all events
            Iterator<SelectionKey> iter = selectedKeys.iterator();

            // Going threw the List and handling the requests
            while (iter.hasNext()) {

                SelectionKey key = iter.next();
                //
                if (key.isAcceptable()) {
                    ServerSocketChannel newSock = (ServerSocketChannel) key.channel();

                    // Here we are accepting the connection to the client and building a connection
                    // Channel to the client
                    SocketChannel clientConnection = newSock.accept();

                    clientConnection.configureBlocking(false);
                    // To hear future incomings
                    clientConnection.register(mainSelector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    sendResponse(clientConnection, readBuffer, "220");

                }
                if (key.isReadable()) {
                    SocketChannel channel = (SocketChannel) key.channel();
                    channel.read(readBuffer);
                    readBuffer.flip();

                    // Buffer jetzt in read modus

                    // Buffer wird decodiert und als String gespeichert

                    CharsetDecoder decoder = messageCharset.newDecoder();
                    CharBuffer charBuf = decoder.decode(readBuffer);

                    bufferContent = charBuf.toString();

                    // SMTP HANDLING
                    responseForClient = handlingSMPTrequests(bufferContent);


                    sendResponse(channel, readBuffer, responseForClient);

                    // IF request is QUIT command
                    if (responseForClient.equals("221")) {
                        channel.close();
                        key.cancel();
                    }
                }
                iter.remove();
            }
        }
    }

    /**
     * Handles SMPT requests from Client
     *
     * @param bufferContent Requests from the Client
     * @return The response code for SMPT Command
     * @throws IOException If an I/O error occurs while processing the request.
     */

    public String handlingSMPTrequests(String bufferContent) throws IOException {
        if (bufferContent.startsWith("HELO")) {
            return "250";
        }

        else if (bufferContent.startsWith("MAIL")) {
            String smptCom = "MAIL FROM:";
            int index = bufferContent.indexOf(smptCom);
            // Cutting out the MAIL Command to get the name of the Client
            sender = bufferContent.substring(index + smptCom.length()).trim();

            return "250";
        }

        else if (bufferContent.startsWith("RCPT")) {
            // Same like MAIL only this time its for RCPT
            String smptCom = "RCPT TO:";
            int index = bufferContent.indexOf(smptCom);
            // Cutting out the RCPT Command to get the name of the Client
            receiver = bufferContent.substring(index + smptCom.length()).trim();

            return "250";
        }

        else if (bufferContent.startsWith("DATA")) {
            dataCounter++;
            return "354";
        }

        else if (bufferContent.startsWith("HELP")) {
            dataCounter--;
            if(dataCounter < 0){
                dataCounter = 0;
            }
            return "214";
        }

        else if (bufferContent.startsWith("QUIT")) {
            return "221";
        }

        else if (bufferContent.endsWith(endOfMailData) || bufferContent.contains(endOfMailDataEscaped) && dataCounter > 0) {
            data = bufferContent.getBytes(messageCharset);
            createDirAndEmail(sender, receiver, data);
            dataCounter--;
            return "250";
        }
        return "500";   // syntax error, command unrecognized (RFC 4.2.1)
    }

    public static void main(String[] args) {
        try {
            initCodes();
            ServerSMTP serverSMTP = new ServerSMTP();
            serverSMTP.workingLoop();
        } catch (IOException e) {
            System.exit(1);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}