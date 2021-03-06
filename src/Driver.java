import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.util.StringTokenizer;

public class Driver {

    private static ArrayList<Tuple> ipLinks; // List of IP Links
    private static ArrayList<Tuple> otnLinks; // List of OTN Links
    private static ArrayList<Tuple> vnLinks; // List of VN Links
    private static String otnTopologyFile;
    private static String ipTopologyFile;
    private static String vnTopologyFile;
    private static String vnLocationFile;
    private static String ipNodeMapFile;
    private static String ipLinkMapFile;
    private static String ipPortInfoFile;

    private static HashMap<String, String> ParseArgs(String[] args) {
        HashMap<String, String> ret = new HashMap<String, String>();
        for (int i = 0; i < args.length; ++i) {
            StringTokenizer tokenizer = new StringTokenizer(args[i], "=");
            ret.put(tokenizer.nextToken(), tokenizer.nextToken());
        }
        return ret;
    }

    public static void main(String[] args) throws IOException {
        HashMap<String, String> parsedArgs = ParseArgs(args);
        // 1-Initialize OTN Graph
        otnLinks = new ArrayList<Tuple>();
        Graph otn = ReadTopology(parsedArgs.get("--otn_topology_file"),
                EndPoint.type.otn, otnLinks);
        // Print graph for testing.
        // System.out.println("******* OTN Graph ******* \n" + otn);

        // 2-Initialize IP Graph & populate ipLinks.
        ipLinks = new ArrayList<Tuple>();
        Graph ip = ReadTopology(parsedArgs.get("--ip_topology_file"),
                EndPoint.type.ip, ipLinks);
        // Print graph for testing
        // System.out.println("******* IP Graph ******* \n" + ip);

        // Read IP Ports and Port Capacities
        ReadPortAttributes(parsedArgs.get("--ip_port_info_file"), ip);
        // for (int i = 0; i < ip.getPorts().length; i++)
        // System.out.println("Node " + i + " has " + ip.getPorts()[i]
        // + " ports each of capacity " + ip.getPortCapacity()[i]);

        // 3- Map IP to OTN
        OverlayMapping ipOtn = ReadOverlayMapping(ip.getAdjList().size(),
                parsedArgs.get("--ip_node_mapping_file"),
                parsedArgs.get("--ip_link_mapping_file"));
        // System.out.println(
        // "******* IP to OTN Overlay Mapping: ******* \n" + ipOtn);

        // 4- Initialize VN Graph
        vnLinks = new ArrayList<Tuple>();
        Graph vn = ReadTopology(parsedArgs.get("--vn_topology_file"),
                EndPoint.type.virtual, vnLinks);
        // Print graph for testing.
        // System.out.println("******* VN Graph ******* \n" + vn);

        // 5- Initialize Location Constraints (Set all IP nodes as candidates
        // for each VN node).
        ArrayList<Integer> locationConstraints[] = ReadLocationConstraints(
                parsedArgs.get("--vn_location_file"), vn.getAdjList().size());
        // for (int i = 0; i < locationConstraints.length; i++) {
        // System.out.println("Location Constraints of node " + i);
        // for (int j = 0; j < locationConstraints[i].size(); j++)
        // System.out.print(locationConstraints[i].get(j) + ",");
        // System.out.println();
        // }

        // 6- Get Initial Solution
        long startTime = System.nanoTime();
        CreateInitialSolution cis = new CreateInitialSolution(ip, otn, ipOtn);
        final int kNumShuffles = Integer
                .parseInt(parsedArgs.get("--num_shuffles"));
        Solutions solution = cis.getInitialSolution(vn, locationConstraints,
                kNumShuffles);
        long elapsedTime = System.nanoTime() - startTime;

        // Write solution status to file.
        String filePrefix = parsedArgs.get("--vn_topology_file");
        FileWriter fw = new FileWriter(filePrefix + ".status");
        BufferedWriter bw = new BufferedWriter(fw);
        if (solution != null) {
            if (solution.getStatus() == "Success") {
                bw.write(solution.getStatus() + "\n");
                WriteSolutionToFile(solution, vn, ip, filePrefix);
                WriteSolutionCostToFile(solution, vn, ip, otn, filePrefix);
            } else {
                bw.write(solution.getStatus() + "\n");
            }
        } else {
            bw.write("Failed\n");
        }
        bw.close();
        fw.close();
        // Write Solution time to file.
        fw = new FileWriter(filePrefix + ".time");
        bw = new BufferedWriter(fw);
        bw.write(Long.toString(elapsedTime / 1000000000) + "."
                + Long.toString(elapsedTime % 1000000000) + "\n");
        bw.close();
        fw.close();

        /*
        HashMap<Tuple, Integer> linkUsage = new HashMap<Tuple, Integer>();
        HashMap<Tuple, ArrayList<Tuple>> revMap = new HashMap<Tuple, ArrayList<Tuple>>();
        for (Tuple vlink : solution.getVnIp().linkMapping.keySet()) {
            int b = vn.getBW(vlink.getSource(), vlink.getDestination(),
                    vlink.getOrder());
            ArrayList<Tuple> ipPath = solution.getVnIp().getLinkMapping(vlink);
            for (Tuple ipLink : ipPath) {
                Tuple key = null;
                if (ipLink.getSource() > ipLink.getDestination()) {
                    key = new Tuple(ipLink.getOrder(), ipLink.getDestination(),
                            ipLink.getSource());
                } else {
                    key = new Tuple(ipLink.getOrder(), ipLink.getSource(),
                            ipLink.getDestination());
                }
                int prevb = 0;
                if (linkUsage.containsKey(key)) {
                    prevb = linkUsage.get(key).intValue();                    
                } else {
                    revMap.put(key, new ArrayList<Tuple>());
                }
                linkUsage.put(key, prevb + b);
                revMap.get(key).add(vlink);
            }
        }
        for (Tuple ipLink : linkUsage.keySet()) {
            int b = ip.getBW(ipLink.getSource(), ipLink.getDestination(),
                    ipLink.getOrder());
            if (b == -1) {
                b = Math.min(ip.getPortCapacity()[ipLink.getSource()],
                        ip.getPortCapacity()[ipLink.getDestination()]);
            }
            int u = linkUsage.get(ipLink);
            System.out.println(ipLink + ": used = " + u + ", cap = " + b);
            System.out.println(ipLink + "--> " + revMap.get(ipLink));
        }*/
    }

    private static void WriteSolutionCostToFile(Solutions solution, Graph vn,
            Graph ip, Graph otn, String filePrefix) throws IOException {
        long cost = 0;
        // First add the cost of vlinks.
        OverlayMapping vnIp = solution.getVnIp();
        for (Tuple vlink : vnIp.linkMapping.keySet()) {
            ArrayList<Tuple> ipPath = vnIp.getLinkMapping(vlink);
            long bw = vn.getBW(vlink.getSource(), vlink.getDestination(),
                    vlink.getOrder());
            for (Tuple link : ipPath) {
                int linkCost = ip.getCost(link.getSource(),
                        link.getDestination(), link.getOrder());
                if (linkCost == -1)
                    linkCost = 1;                
                cost += (bw * linkCost);
            }
        }

        // Add the cost of new IP links.
        int offset = ip.getNodeCount();
        OverlayMapping ipOtn = solution.getIpOtn();
        for (Tuple ipLink : ipOtn.linkMapping.keySet()) {
            ArrayList<Tuple> otnPath = ipOtn.getLinkMapping(ipLink);
            long bw = Math.min(ip.getPortCapacity()[ipLink.getSource()],
                    ip.getPortCapacity()[ipLink.getDestination()]);
            for (Tuple link : otnPath) {
                int linkCost = otn.getCost(link.getSource() - offset,
                        link.getDestination() - offset, link.getOrder());
                // System.out.println("IP bw = " + bw + "; OTN Link Cost = "
                // + linkCost + "\n");
                cost += (bw * linkCost);
            }
        }

        FileWriter fw = new FileWriter(filePrefix + ".cost");
        BufferedWriter bw = new BufferedWriter(fw);
        bw.write(Long.toString(cost) + "\n");
        bw.close();
        fw.close();
    }

    private static void WriteSolutionToFile(Solutions solution, Graph vn,
            Graph ip, String filePrefix) throws IOException {
        OverlayMapping vnIp = solution.getVnIp();
        // Write vnode mapping to file.
        FileWriter fw = new FileWriter(filePrefix + ".nmap");
        BufferedWriter bw = new BufferedWriter(fw);
        for (int i = 0; i < vn.getNodeCount(); ++i) {
            bw.write(Integer.toString(i) + ","
                    + Integer.toString(vnIp.getNodeMapping(i)) + "\n");
        }
        bw.close();
        fw.close();

        // Write vlink mapping to file.
        fw = new FileWriter(filePrefix + ".emap");
        bw = new BufferedWriter(fw);
        for (Tuple vlink : vnIp.linkMapping.keySet()) {
            ArrayList<Tuple> ipPath = vnIp.getLinkMapping(vlink);
            for (Tuple link : ipPath) {
                bw.write(Integer.toString(vlink.getSource()) + ","
                        + Integer.toString(vlink.getDestination()) + ","
                        + Integer.toString(link.getSource()) + ","
                        + Integer.toString(link.getDestination()) + ","
                        + Integer.toString(link.getOrder()) + "\n");
            }
        }
        bw.close();
        fw.close();

        // Write new IP links to file.
        int offset = ip.getNodeCount();
        fw = new FileWriter(filePrefix + ".new_ip");
        bw = new BufferedWriter(fw);
        OverlayMapping ipOtn = solution.getIpOtn();
        for (Tuple ipLink : ipOtn.linkMapping.keySet()) {
            ArrayList<Tuple> otnPath = ipOtn.getLinkMapping(ipLink);
            for (Tuple link : otnPath) {
                bw.write(Integer.toString(ipLink.getSource()) + ","
                        + Integer.toString(ipLink.getDestination()) + ","
                        + Integer.toString(ipLink.getOrder()) + ","
                        + Integer.toString(link.getSource() - offset) + ","
                        + Integer.toString(link.getDestination() - offset)
                        + "\n");
            }
        }
        bw.close();
        fw.close();
    }

    public static String ReadFromFile(String filename) throws IOException {
        String content = "";
        BufferedReader br = new BufferedReader(new FileReader(filename));
        try {
            StringBuilder sb = new StringBuilder();
            String line = br.readLine();
            while (line != null) {
                content += line + "\n";
                line = br.readLine();
            }
        } finally {
            if (br != null)
                br.close();
        }
        return content;
    }

    /* This function reads the port info for the IP */
    public static void ReadPortAttributes(String filename, Graph ip)
            throws IOException {
        String content = ReadFromFile(filename);
        Scanner scanner = new Scanner(content);
        while (scanner.hasNextLine()) {
            String[] line = scanner.nextLine().split(",");
            ip.getPorts()[Integer.parseInt(line[0])] = Integer
                    .parseInt(line[1]);
            ip.getPortCapacity()[Integer.parseInt(line[0])] = Integer
                    .parseInt(line[2]);
        }
    }

    /* This function reads the location constraints */
    public static ArrayList<Integer>[] ReadLocationConstraints(String fileName,
            int n) throws IOException {
        ArrayList<Integer>[] loc = new ArrayList[n];

        String content = ReadFromFile(fileName);
        Scanner scanner = new Scanner(content);
        while (scanner.hasNextLine()) {
            String[] line = scanner.nextLine().split(",");
            loc[Integer.parseInt(line[0])] = new ArrayList<Integer>();
            for (int i = 1; i < line.length; i++)
                loc[Integer.parseInt(line[0])].add(Integer.parseInt(line[i]));
        }
        return loc;
    }

    /* This function reads the IP-OTN OverlayMapping Solution */
    public static OverlayMapping ReadOverlayMapping(int n,
            String nodeMappingFile, String linkMappingFile) throws IOException {

        // Create OverlayMapping Solution
        OverlayMapping ipOtn = new OverlayMapping(n);

        // Read Node Mapping Solution
        Scanner scanner = new Scanner(ReadFromFile(nodeMappingFile));
        while (scanner.hasNextLine()) {
            String[] line = scanner.nextLine().split(",");
            ipOtn.nodeMapping[Integer.parseInt(line[0])] = Integer
                    .parseInt(line[1]);
        }

        // Read Link Mapping Solution
        scanner = new Scanner(ReadFromFile(linkMappingFile));
        while (scanner.hasNextLine()) {
            String[] splitLine = scanner.nextLine().split(",");
            Tuple ipLink = new Tuple(0, Integer.parseInt(splitLine[0]),
                    Integer.parseInt(splitLine[1]));

            for (int i = 0; i < ipLinks.size(); i++) {
                if (ipLinks.get(i).equals(ipLink))
                    ipOtn.setLinkMapping(ipLinks.get(i),
                            new Tuple(0, Integer.parseInt(splitLine[2]),
                                    Integer.parseInt(splitLine[3])));
            }
        }
        return ipOtn;
    }

    /* This function reads a graph from file */
    public static Graph ReadTopology(String filename, EndPoint.type graphType,
            ArrayList<Tuple> links) throws IOException {

        Scanner scanner = new Scanner(ReadFromFile(filename));
        Graph g = new Graph(Integer.parseInt(scanner.nextLine())); // Initialize
                                                                   // Graph with
                                                                   // the number
                                                                   // of Nodes
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (line != null) {
                // Split the line to get 0: index of the first node; 1: index of
                // the second node; 2: bandwidth
                String[] splitLine = line.split(",");
                // Create Tuple for this link
                int src = Integer.parseInt(splitLine[0]);
                int dst = Integer.parseInt(splitLine[1]);
                int order = g.findTupleOrder(src, dst);
                links.add(new Tuple(order,src,dst));
                // Create two end points
                EndPoint ep1 = new EndPoint(Integer.parseInt(splitLine[1]),
                        Integer.parseInt(splitLine[2]),
                        Integer.parseInt(splitLine[3]), graphType, order);
                g.addEndPoint(Integer.parseInt(splitLine[0]), ep1);
                if (ep1.getBw() < 0)
                    System.out.println("Negative bandwidth in input: " + ep1);
                EndPoint ep2 = new EndPoint(Integer.parseInt(splitLine[0]),
                        Integer.parseInt(splitLine[2]),
                        Integer.parseInt(splitLine[3]), graphType, order);
                g.addEndPoint(Integer.parseInt(splitLine[1]), ep2);
                if (ep2.getBw() < 0)
                    System.out.println("Negative bandwidth in input: " + ep2);
            }
        }
        return g;
    }

}
