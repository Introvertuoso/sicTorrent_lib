
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.TimeoutException;

import static java.lang.Math.pow;

public abstract class Tracker {
    public Tracker() {
        status=TrackerStatus.NONE;
        seeds = 0;
        leeches = 0;
        downloaded=0;
        interval = -1;
    }
    public void setStatus(TrackerStatus s){status=s;}
    public static Tracker createTracker(String s) throws InvalidTrackerException,UnknownHostException {
        URL url;
        try {
            if (s.substring(0, 4).toUpperCase().equals("HTTP")) {
                url = new URL(s);
                return new HTTPTracker(s);
            } else
                throw new MalformedURLException();
        } catch (MalformedURLException e) {

            if (s.substring(0, 3).toLowerCase().equals("udp")) {
                InetAddress address;
                try {
                    address = InetAddress.getByName(s.substring(6, s.lastIndexOf(':')));
                } catch (UnknownHostException ex) {
                    throw ex;
                }
                int port;
                try {

                    if (s.lastIndexOf('/') == -1)
                        port = Integer.parseInt(s.substring(s.lastIndexOf(':') + 1));
                    else {
                        int start = s.lastIndexOf(':') + 1;
                        char arr[] = s.toCharArray();
                        int i = start;
                        int end = 0;
                        while (i < s.length()) {
                            if ((int) arr[i] >= 48 && (int) arr[i] <= 57)
                                i++;
                            else {
                                end = i;
                                break;
                            }
                        }
                        if (i == s.length())
                            end = i;
                        port = Integer.parseInt(s.substring(start, end));
                    }
                    return new UDPTracker(address, port, s);
                } catch (NumberFormatException ex) {
                    throw new InvalidTrackerException(ex.getMessage());
                }

            } else
                throw new InvalidTrackerException("else");
        } catch (Exception e) {
            throw new InvalidTrackerException("finally");
        }
    }
    public static boolean checkIfExists(String uri,ArrayList<Tracker> trackers)
    {
        for (Tracker t:trackers)
            if (t.getUri().toLowerCase().equals(uri.toLowerCase()))
                return true;
            return false;
    }
    public abstract ArrayList<Pair<String, Integer>> announce(
            byte infohash[], long uploaded, long downloaded, long left, AnnounceEvent event)
            throws TimeoutException, IOException, InterruptedException, InvalidReplyException;

    public abstract ScrapeResult scrape(byte infohash[]) throws
            TimeoutException, IOException, InterruptedException, InvalidReplyException;

    public abstract int getInterval();

    public int getSeeds() {
        return seeds;
    }

    public int getLeeches() {
        return leeches;
    }

    public String getUri() {
        return uri;
    }

    public boolean isEnabled() {
        return status!=TrackerStatus.DISABLED;
    }

    public TrackerStatus getStatus() {
        return status;
    }

    protected int interval;
    protected int seeds;
    protected int leeches;
    protected int downloaded;
    protected String uri;
    protected Integer key;
    protected TrackerStatus status;
}

class UDPTracker extends Tracker {
    public synchronized int getInterval(){return interval;}
    private InetAddress address;
    private int port;
    private long connectionID = 0;
    private final long protocolID = Long.parseLong("41727101980", 16);
    private DatagramSocket socket;
    private Random rand;
    private Integer TransactionID;

    private void genTID(){
        if (TransactionID==null)
        {
            TransactionID=rand.nextInt();
            Thread t = new Thread(()->{try{Thread.sleep(2*60*1000);TransactionID=null;}catch(Exception e){}});
            t.setDaemon(true);
            t.start();
        }
    }
    public UDPTracker(InetAddress address, int port, String uri) {
        this.address = address;
        this.port = port;
        rand = new Random();
        this.uri = uri;
    }

    public synchronized ArrayList<Pair<String, Integer>> announce(
            byte infohash[], long uploaded, long downloaded, long left,
            AnnounceEvent event) throws TimeoutException, InterruptedException, IOException, InvalidReplyException {
        ArrayList<Pair<String, Integer>> list = new ArrayList<>();
        status=TrackerStatus.ANNOUNCING;
        if (socket == null) {
            socket = new DatagramSocket();
            socket.setSoTimeout(1500);
        }
        interval=-1;
        connect();
        genTID();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream ostream = new DataOutputStream(baos);
        ostream.writeLong(connectionID);               //Connection ID
        ostream.writeInt(1);                        //Action (1 is announce)
        ostream.writeInt(TransactionID);               //Transaction ID
        ostream.write(infohash);                       //Info Hash
        ostream.write(Info.getPeerID().getBytes());    //Peer ID
        ostream.writeLong(downloaded);                 //Downloaded
        ostream.writeLong(left);                       //Left
        ostream.writeLong(uploaded);                   //Uploaded
        switch (event) {                               //Event
            case STARTED:
                ostream.writeInt(2);
                break;
            case COMPLETED:
                ostream.writeInt(1);
                break;
            case NONE:
                ostream.writeInt(0);
                break;
            case STOPPED:
                ostream.writeInt(3);
                break;
        }
        ostream.writeInt(0);                        //IP (0 is default)
        if (key==null) {
            key = rand.nextInt();
        }
        ostream.writeInt(key);                         //key (random)
        ostream.writeInt(50);                       //numwant (-1 is default)
        ostream.writeShort(Info.getPort());            //My Port
        ostream.close();

        DatagramPacket outgoing = new DatagramPacket(baos.toByteArray(), baos.toByteArray().length,
                address, port);

        byte response[] = new byte[1024];
        DatagramPacket incoming = new DatagramPacket(response, 1024);
        sendreceive(incoming, outgoing);

        ByteArrayInputStream bais = new ByteArrayInputStream(response);
        DataInputStream istream = new DataInputStream(bais);

        int action = istream.readInt();
        synchronized(TransactionID) {
            if (istream.readInt() != TransactionID)
                System.out.println("Incorrect transaction ID");
        }
        if (action == 3) {
            istream.close();
            BufferedReader reader = new BufferedReader(new InputStreamReader(bais));
            String str = reader.readLine();
            reader.close();
            throw new InvalidReplyException(str);
        }
        interval = istream.readInt();
        leeches = istream.readInt();
        seeds = istream.readInt();
        for (int i = 20; i < incoming.getLength(); i += 6) {
            String add = new String();
            for (int j = 0; j < 4; j++) {
                add += String.valueOf(Byte.toUnsignedInt(response[i + j]));
                if (j != 3)
                    add += ".";
            }
            int port = Byte.toUnsignedInt(response[i + 4]) * 256 + Byte.toUnsignedInt(response[i + 5]);
            list.add(new Pair<>(add, port));
        }
        istream.close();
        status=TrackerStatus.WORKING;
        return list;
    }

    public synchronized ScrapeResult scrape(byte infohash[]) throws
            TimeoutException, IOException, InterruptedException, InvalidReplyException {
        if (socket == null) {
            socket = new DatagramSocket();
            socket.setSoTimeout(1500);
        }
        interval=-1;
        connect();
        status=TrackerStatus.SCRAPING;
        genTID();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream ostream = new DataOutputStream(baos);

        ostream.writeLong(connectionID);         //Connection ID
        ostream.writeInt(2);                  //Action (2 for scrape)
        ostream.writeInt(TransactionID);         //Transaction ID
        ostream.write(infohash);                 //Info Hash
        ostream.close();

        DatagramPacket outgoing = new DatagramPacket(baos.toByteArray(), baos.toByteArray().length,
                address, port);

        byte response[] = new byte[20];
        DatagramPacket incoming = new DatagramPacket(response, 20);
        sendreceive(incoming, outgoing);

        ByteArrayInputStream bais = new ByteArrayInputStream(response);
        DataInputStream istream = new DataInputStream(bais);

        int action = istream.readInt();
        if (istream.readInt() != TransactionID)
            throw new InvalidReplyException("Incorrect transaction ID");
        if (action == 3) {
            istream.close();
            BufferedReader reader = new BufferedReader(new InputStreamReader(bais));
            String str = reader.readLine();
            reader.close();
            throw new InvalidReplyException(str);
        }
        int completed = istream.readInt();
        int downloaded = istream.readInt();
        int incomplete = istream.readInt();
        istream.close();
        status=TrackerStatus.SCRAPEOK;
        return new ScrapeResult(completed, incomplete, downloaded);
    }

    public void connect() throws InvalidReplyException, InterruptedException, IOException, TimeoutException {
        genTID();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream ostream = new DataOutputStream(baos);
        ostream.writeLong(protocolID);            //protocol
        ostream.writeInt(0);                   //action (0 is connect)
        ostream.writeInt(TransactionID);          //transaction ID
        ostream.close();

        DatagramPacket outgoing = new DatagramPacket(baos.toByteArray(), baos.toByteArray().length,
                address, port);

        byte response[] = new byte[16];
        DatagramPacket incoming = new DatagramPacket(response, 16);
        sendreceive(incoming, outgoing);

        ByteArrayInputStream bais = new ByteArrayInputStream(response);
        DataInputStream istream = new DataInputStream(bais);
        int action = istream.readInt();
        synchronized(TransactionID) {
            if (istream.readInt() != TransactionID)
                throw new InvalidReplyException("Incorrect transaction ID");
        }
        if (action == 3) {
            istream.close();
            BufferedReader reader = new BufferedReader(new InputStreamReader(bais));
            String str = reader.readLine();
            reader.close();
            throw new InvalidReplyException(str);
        }
        connectionID = istream.readLong();
        istream.close();
    }

    private void sendreceive(DatagramPacket incoming, DatagramPacket outgoing)
            throws IOException, TimeoutException, InterruptedException {
        int n = 0;
        while (n <= 8) {
            socket.send(outgoing);
            try {
                socket.receive(incoming);
                return;
            } catch (SocketTimeoutException e) {
                Thread.sleep(15 * (int) pow(2, n++));
                continue;
            }
        }
        status = TrackerStatus.TIMEDOUT;
        interval = 300;
        throw new TimeoutException();
    }
}

class HTTPTracker extends Tracker {

    public synchronized int getInterval(){return interval;}
    private boolean scrapable;

    public HTTPTracker(String str) {
        this.uri = str; scrapable=true;
    }

    public synchronized ArrayList<Pair<String, Integer>> announce(
            byte infohash[], long uploaded, long downloaded, long left,
            AnnounceEvent event) throws TimeoutException, InterruptedException, IOException, InvalidReplyException {
        Parcel parcel;
        status=TrackerStatus.ANNOUNCING;

        interval=-1;
        String str = new String();
        str += uri;
        str += "?info_hash=" + URLByteEncoder.encode(infohash);
        str += "&peer_id=" + Info.getPeerID();
        str += "&port=" + Info.getPort();
        str += "&uploaded=" + String.valueOf(uploaded);
        str += "&downloaded=" + String.valueOf(downloaded);
        str += "&left=" + String.valueOf(left);
        str += "&compact=1";
        if (key==null)
            key = new Random().nextInt();
        str += "&key=" + key;
        switch (event) {
            case STARTED:
                str += "&event=started";
                break;
            case STOPPED:
                str += "&event=stopped";
                break;
            case COMPLETED:
                str += "&event=completed";
                break;
            default:
                break;
        }
        URL url;
        byte res[] = null;
        try {
            url = new URL(str);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream is = url.openStream();
            byte chunk[] = new byte[16];
            int n;
            if (is.available()==0)
            {
                Thread.sleep(5000);
                if (is.available()==0)
                {
                    status = TrackerStatus.TIMEDOUT;
                    throw new TimeoutException();
                }
            }
            while ((n = is.read(chunk)) > 0)
                baos.write(chunk, 0, n);
            res = baos.toByteArray();

            is.close();
        } catch (MalformedURLException e) {
            status = TrackerStatus.TIMEDOUT;
            throw new TimeoutException();
        }
        try {
            parcel = bCoder.decode(res, ParcelType.RESPONSE);
        } catch (Exception e) {
            e.printStackTrace();
            throw new InvalidReplyException(e.getMessage() + new String(res, StandardCharsets.UTF_8));
        }
        ArrayList<Pair<String, Integer>> list = new ArrayList<>();
        if (parcel.getFailureReason() != null)
            throw new InvalidReplyException(parcel.getFailureReason());
        interval = parcel.getInterval();
        if (interval == 0) {
            interval = -1;
            throw new InvalidReplyException("Interval is 0");
        }
        seeds = parcel.getComplete();
        leeches = parcel.getIncomplete();
        if (parcel.getPeers().size() != 0)
            for (byte peer[] : parcel.getPeers()) {
                String address = new String();
                for (int i = 0; i < 4; i++) {
                    address += String.valueOf(Byte.toUnsignedInt(peer[i]));
                    if (i != 3)
                        address += ".";
                }
                int port = Byte.toUnsignedInt(peer[4]) * 256 + Byte.toUnsignedInt(peer[5]);
                list.add(new Pair<>(address, port));
            }

        else if (parcel.getPeerIP().size() == parcel.getPeerPort().size() && parcel.getPeerIP().size() != 0)
            for (int i = 0; i < parcel.getPeerIP().size(); i++)
                list.add(new Pair<>(parcel.getPeerIP().get(i), parcel.getPeerPort().get(i)));
        else
        {
            status = TrackerStatus.TIMEDOUT;
            throw new InvalidReplyException("Missing Peers");
        }
        status=TrackerStatus.WORKING;
        return list;
    }

    public synchronized ScrapeResult scrape(byte infohash[]) throws
            TimeoutException, IOException, InterruptedException, InvalidReplyException {
        if (scrapable==false)
            throw new TimeoutException();
        String str = getScrapeUri();
        interval=-1;
        str += "?info_hash=" + URLByteEncoder.encode(infohash);
        status=TrackerStatus.SCRAPING;
        URL url;
        byte res[] = null;
        try {
            url = new URL(str);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream is = url.openStream();
            byte chunk[] = new byte[16];
            int n;
            while ((n = is.read(chunk)) > 0)
                baos.write(chunk, 0, n);
            res = baos.toByteArray();

            is.close();
        } catch (MalformedURLException e) {
            scrapable=false;
            throw new TimeoutException();
        }

        Parcel parcel;
        try {
            parcel = bCoder.decode(res, ParcelType.SCRAPE);
        } catch (InvalidBencodeException e) {
            e.printStackTrace();
            throw new InvalidReplyException(e.getMessage());
        }
        if (!new String(infohash, StandardCharsets.UTF_8).equals(parcel.getUTF8Hash()))
            throw new InvalidReplyException("Hash mismatch");
        status=TrackerStatus.SCRAPEOK;
        return new ScrapeResult(parcel.getComplete(), parcel.getIncomplete(), parcel.getDownloaded());
    }

    private String getScrapeUri() {
        String str = new String(uri);
        int index = str.lastIndexOf('/');
        if (index + 8 >= str.length())
            return null;
        if (str.substring(index + 1, index + 9).toLowerCase().equals("announce")) {
            if (index + 9 == str.length())
                return str.substring(0, index + 1) + "scrape";
            else {
                String ending = str.substring(index + 9);
                return str.substring(0, index + 1) + "scrape" + ending;
            }
        } else
            return null;
    }
}

