package monster.giz;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class StellarisSaveAnalyzer {

    public static final String SAVE_GAMES_PATH = "C:\\Users\\ooomz\\Documents\\Paradox Interactive\\Stellaris\\save games";
    public static final String TEMP_DIR = "C:\\Users\\ooomz\\Documents\\Paradox Interactive\\Stellaris\\temp";

    public static List<Path> saveFolders = new ArrayList<>();

    public static List<String> specialSystems;
    public static List<String> specialFlags;

    public static Path currentExtractedGalaxyPath;

    public static HashMap<Integer, SystemData> currentGalaxyMap = new HashMap<>();
    public static List<SystemData> locatedSpecialSystems = new ArrayList<>();
    public static Map<SystemData, List<String>> locatedSpecialFlagSystems = new HashMap<>();

    public static SystemData selectedSystem = null;

    public static void main(String[] args) {
        prepareFiles();
        System.out.println("Stellaris Save Peeker - v1 \n");
        if (saveFolders.isEmpty()) {
            System.out.println("No save folders found.");
            return;
        }
        process();
    }

    public static void prepareFiles() {
        try {
            saveFolders = collectSavePaths(SAVE_GAMES_PATH);
            specialSystems = loadResourceList("special_systems.txt");
            specialFlags = loadResourceList("special_flags.txt");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<Path> collectSavePaths(String directoryPath) throws IOException {
        Path dirPath = Paths.get(directoryPath);
        return Files.list(dirPath)
                .filter(Files::isDirectory)
                .sorted((p1, p2) -> {
                    try {
                        return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .collect(Collectors.toList());
    }

    public static void process() {
        System.out.println("Available Saves: ");
        displaySaveFolders(saveFolders);
        int saveChoice = getUserChoice(saveFolders.size());
        if (saveChoice == -1) return;

        Path selectedFolder = saveFolders.get(saveChoice - 1);
        Path ironmanSavePath = selectedFolder.resolve("ironman.sav");

        if (!Files.exists(ironmanSavePath)) {
            System.out.println("No ironman.sav file found in the selected folder.");
            return;
        }

        if (createUniqueExtractionDirectory(TEMP_DIR, stripNumbers(String.valueOf(selectedFolder.getFileName())))) {
            System.out.println("1. Extracted " + selectedFolder.getFileName() + " to " + currentExtractedGalaxyPath.getFileName());
        }

        try {
            extractStellarisSaveData(ironmanSavePath.toString(), currentExtractedGalaxyPath.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Path gamestatePath = currentExtractedGalaxyPath.resolve("gamestate");
        try {
            String galacticObjectContent = processGamestateFile(gamestatePath);
            parseGalacticObjectBlock(galacticObjectContent);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        outputGalaxyOverview();
        locateSpecialSystems();
        outputHomeSystemSection();

        Map<Integer, List<Integer>> adjacencyMap = compileAdjacencyMap();
        outputNavigationSection(adjacencyMap);
    }

    private static void outputGalaxyOverview() {
        System.out.println("\n - - - Galaxy Overview - - - ");
        System.out.println("Total Galactic Objects: " + currentGalaxyMap.size());
        double galacticDiameter = approximateGalaxyDiameter();
        System.out.printf("Approximate Galactic Diameter: %.2f units%n", galacticDiameter);
        System.out.println("====================================");
    }

    private static void outputHomeSystemSection() {
        selectedSystem = getUserStartingSystem();
        if (selectedSystem == null) {
            System.out.println("\nStarting system not found.");
            return;
        }

        System.out.println("\n=== Home System and Nearby Systems ===");
        System.out.println("Your starting system: " + selectedSystem);
        System.out.println("\nDistances and Directions from Your Starting System:");
        for (SystemData system : locatedSpecialSystems) {
            outputSystemDistanceDirectionAngle(selectedSystem, system, approximateGalaxyDiameter());
        }

        for (Map.Entry<SystemData, List<String>> entry : locatedSpecialFlagSystems.entrySet()) {
            SystemData system = entry.getKey();
            outputSystemDistanceDirectionAngle(selectedSystem, system, approximateGalaxyDiameter());
        }

        Map<String, Long> commonFlags = findCommonFlagsInNearbySystems(selectedSystem, 50.0);
        String mostCommonPrecursor = findMostCommonPrecursor(selectedSystem);

        System.out.println("\nAnalysis of Nearby Systems:");
        System.out.println("1. Precursor: " + (mostCommonPrecursor != null ? mostCommonPrecursor : "Not found"));
        System.out.println("2. Most Common Flags in Nearby Systems:");
        commonFlags.entrySet().stream()
                .filter(entry -> !entry.getKey().startsWith("precursor_"))
                .sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
                .forEach(entry -> {
                    System.out.printf("   - %s: %d occurrences%n", entry.getKey(), entry.getValue());
                });
        System.out.println("====================================");
    }

    private static void outputNavigationSection(Map<Integer, List<Integer>> adjacencyMap) {
        System.out.println("\n=== Navigation to Points of Interest ===");
        for (SystemData system : locatedSpecialSystems) {
            outputPath(findShortestPath(selectedSystem.id, system.id, adjacencyMap));
        }
        for (SystemData system : locatedSpecialFlagSystems.keySet()) {
            outputPath(findShortestPath(selectedSystem.id, system.id, adjacencyMap));
        }
        System.out.println("====================================");
    }

    public static void outputPath(List<Integer> path) {
        if (path.isEmpty()) {
            System.out.println("No path found.");
            return;
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            result.append(currentGalaxyMap.get(path.get(i)).name);
            if (i < path.size() - 1) {
                result.append(" -> ");
            }
        }

        System.out.println(result.toString());
    }

    public static List<Integer> findShortestPath(int startId, int endId, Map<Integer, List<Integer>> adjacencyMap) {
        Queue<List<Integer>> queue = new LinkedList<>();
        Set<Integer> visited = new HashSet<>();

        queue.add(Arrays.asList(startId));

        while (!queue.isEmpty()) {
            List<Integer> path = queue.poll();
            int currentSystem = path.get(path.size() - 1);

            if (currentSystem == endId) {
                return path;
            }

            if (visited.contains(currentSystem)) {
                continue;
            }

            visited.add(currentSystem);
            for (int neighbor : adjacencyMap.getOrDefault(currentSystem, Collections.emptyList())) {
                if (!visited.contains(neighbor)) {
                    List<Integer> newPath = new ArrayList<>(path);
                    newPath.add(neighbor);
                    queue.add(newPath);
                }
            }
        }

        return Collections.emptyList();
    }

    private static boolean isDeadEnd(int systemId, int comingFrom, Map<Integer, List<Integer>> adjacencyMap) {
        List<Integer> neighbors = adjacencyMap.getOrDefault(systemId, Collections.emptyList());
        return neighbors.size() == 0 || (neighbors.size() == 1 && neighbors.get(0) == comingFrom);
    }

    public static Map<Integer, List<Integer>> compileAdjacencyMap() {
        Map<Integer, List<Integer>> adjacencyMap = new HashMap<>();

        for (Map.Entry<Integer, SystemData> entry : currentGalaxyMap.entrySet()) {
            int systemId = entry.getKey();
            SystemData systemData = entry.getValue();

            List<Integer> connectedSystems = new ArrayList<>();
            if (systemData.hyperlanes != null) {
                for (Hyperlane hyperlane : systemData.hyperlanes) {
                    connectedSystems.add(hyperlane.to);
                }
            }
            adjacencyMap.put(systemId, connectedSystems);
        }

        return adjacencyMap;
    }

    public static void locateSpecialSystems() {
        for (SystemData system : currentGalaxyMap.values()) {
            if (specialSystems.contains(system.name)) {
                locatedSpecialSystems.add(system);
            }
            if (system.flags != null && system.flags.length > 0) {
                List<String> matchedFlags = Arrays.stream(system.flags)
                        .filter(specialFlags::contains)
                        .collect(Collectors.toList());
                if (!matchedFlags.isEmpty()) {
                    locatedSpecialFlagSystems.put(system, matchedFlags);
                }
            }
        }
    }

    private static void outputSystemDistanceDirectionAngle(SystemData from, SystemData to, double galaxyWidth) {
        double distance = calculateDistance(from, to);
        double percentDistance = (distance / galaxyWidth) * 100;
        String direction = calculateDirection(from, to);
        double angle = calculateAngle(from, to);

        System.out.printf("   - %s is %.2f units away (%.2f%% of galaxy, direction: %s, angle: %.2f°)%n", to.name, distance, percentDistance, direction, angle);
    }

    private static Map<String, Long> findCommonFlagsInNearbySystems(SystemData startingSystem, double maxDistance) {
        return currentGalaxyMap.values().stream()
                .filter(system -> calculateDistance(startingSystem, system) <= maxDistance)
                .flatMap(system -> system.flags != null ? Arrays.stream(system.flags) : Stream.empty())
                .collect(Collectors.groupingBy(flag -> flag, Collectors.counting()));
    }

    private static String findMostCommonPrecursor(SystemData homeSystem) {
        Map<String, String> precursorMap = Map.of(
                "precursor_1", "Vultaum",
                "precursor_2", "Yuht",
                "precursor_3", "First League",
                "precursor_4", "Irassians",
                "precursor_5", "Cybrex",
                "precursor_zroni_1", "Zroni",
                "precursor_baol_1", "Baol"
        );

        if (homeSystem.flags != null) {
            for (String flag : homeSystem.flags) {
                if (precursorMap.containsKey(flag)) {
                    return precursorMap.get(flag);
                }
            }
        }

        Map<String, Long> commonFlags = findCommonFlagsInNearbySystems(homeSystem, 75.0);

        return commonFlags.entrySet().stream()
                .filter(entry -> precursorMap.containsKey(entry.getKey()))
                .max(Map.Entry.comparingByValue())
                .map(entry -> precursorMap.get(entry.getKey()))
                .orElse(null);
    }

    private static String calculateDirection(SystemData from, SystemData to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        String horizontal = dx > 0 ? "West" : "East";
        String vertical = dy > 0 ? "South" : "North";

        if (Math.abs(dx) > Math.abs(dy)) {
            return horizontal;
        } else if (Math.abs(dy) > Math.abs(dx)) {
            return vertical;
        } else {
            return vertical + "-" + horizontal;
        }
    }

    private static double calculateAngle(SystemData from, SystemData to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        return angle < 0 ? angle + 360 : angle;
    }

    private static double approximateGalaxyDiameter() {
        double maxDistance = 0;

        List<SystemData> systems = new ArrayList<>(currentGalaxyMap.values());

        for (int i = 0; i < systems.size(); i++) {
            for (int j = i + 1; j < systems.size(); j++) {
                double distance = calculateDistance(systems.get(i), systems.get(j));
                if (distance > maxDistance) {
                    maxDistance = distance;
                }
            }
        }

        return maxDistance;
    }

    private static double calculateDistance(SystemData system1, SystemData system2) {
        double dx = system1.x - system2.x;
        double dy = system1.y - system2.y;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static SystemData getUserStartingSystem() {
        Scanner scanner = new Scanner(System.in);
        SystemData matchedSystem = null;

        while (matchedSystem == null) {
            System.out.print("\nEnter the name of your starting system: ");
            String input = scanner.nextLine().trim();

            matchedSystem = currentGalaxyMap.values().stream()
                    .filter(system -> system.name.equalsIgnoreCase(input))
                    .findFirst()
                    .orElse(null);

            if (matchedSystem == null) {
                String prefixedInput = "NAME_" + input;
                matchedSystem = currentGalaxyMap.values().stream()
                        .filter(system -> system.name.equalsIgnoreCase(prefixedInput))
                        .findFirst()
                        .orElse(null);
            }

            if (matchedSystem == null) {
                System.out.println("System not found. Please try again.");
            }
        }

        return matchedSystem;
    }

    private static String stripNumbers(String name) {
        return name.split("_")[0];
    }

    private static List<String> loadResourceList(String filename) throws IOException {
        InputStream inputStream = StellarisSaveAnalyzer.class.getClassLoader().getResourceAsStream(filename);

        if (inputStream == null) {
            System.out.println("No " + filename + " file found in the classpath.");
            return new ArrayList<>();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toList());
        }
    }

    private static String processGamestateFile(Path gamestatePath) throws IOException {
        if (!Files.exists(gamestatePath)) {
            System.out.println("No gamestate file found.");
            return "";
        }

        System.out.println("Found gamestate file at: " + gamestatePath.toString());
        return captureTopLevelGalacticObjectBlock(gamestatePath);
    }

    public static void displaySaveFolders(List<Path> saveFolders) {
        for (int i = 0; i < saveFolders.size(); i++) {
            System.out.println((i + 1) + ": " + saveFolders.get(i).getFileName());
        }
    }

    public static int getUserChoice(int maxChoice) {
        Scanner scanner = new Scanner(System.in);
        System.out.print("\nEnter the number of the save folder you want to analyze: ");
        int choice = scanner.nextInt();

        if (choice < 1 || choice > maxChoice) {
            System.out.println("Invalid choice.");
            return -1;
        }

        return choice;
    }

    public static boolean createUniqueExtractionDirectory(String tempDir, String empireName) {
        try {
            String epochTime = String.valueOf(System.currentTimeMillis());
            Path extractionDir = Paths.get(tempDir, empireName + "_extracted_" + epochTime);

            Files.createDirectories(extractionDir);
            currentExtractedGalaxyPath = extractionDir;
            return true;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void extractStellarisSaveData(String zipFilePath, String outputDirPath) throws IOException {
        try (FileInputStream fis = new FileInputStream(zipFilePath);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry zipEntry = zis.getNextEntry();
            while (zipEntry != null) {
                File newFile = new File(outputDirPath, zipEntry.getName());
                if (zipEntry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();

                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        byte[] buffer = new byte[1024];
                        int length;
                        while ((length = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, length);
                        }
                    }
                }
                zipEntry = zis.getNextEntry();
            }
            zis.closeEntry();
        }
    }

    public static String captureTopLevelGalacticObjectBlock(Path gamestatePath) throws IOException {
        List<String> lines = Files.readAllLines(gamestatePath);
        boolean foundTopLevelGalacticObject = false;
        int bracketDepth = 0;
        StringBuilder galacticObjectContent = new StringBuilder();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();

            if (line.equals("galactic_object=") && i + 1 < lines.size() && lines.get(i + 1).trim().equals("{")) {
                foundTopLevelGalacticObject = true;
                bracketDepth++;
                galacticObjectContent.append(line).append("\n");
                galacticObjectContent.append(lines.get(i + 1).trim()).append("\n");
                i++;
                continue;
            }

            if (foundTopLevelGalacticObject) {
                if (line.contains("{")) {
                    bracketDepth++;
                }
                if (line.contains("}")) {
                    bracketDepth--;
                }

                galacticObjectContent.append(line).append("\n");

                if (bracketDepth == 0) {
                    break;
                }
            }
        }

        return foundTopLevelGalacticObject ? galacticObjectContent.toString() : null;
    }

    public static void parseGalacticObjectBlock(String galacticObjectContent) {
        String[] objects = galacticObjectContent.split("(?<=\\})\\s*(?=\\d+=)");

        for (String object : objects) {
            SystemData systemData = parseSystemData(object);
            if (systemData != null) {
                currentGalaxyMap.put(systemData.id, systemData);
            } else {
                System.out.println("Could not load system data: " + object);
            }
        }
    }

    private static SystemData parseSystemData(String object) {
        int id = extractSystemId(object);
        String name = extractSystemName(object);
        double[] coordinates = extractCoordinates(object);
        List<String> flags = extractFlags(object);
        List<Hyperlane> hyperlanes = extractHyperlanes(object);

        if (name != null && coordinates[0] != 0 && coordinates[1] != 0) {
            SystemData data = new SystemData(id, name, coordinates[0], coordinates[1]);
            if (!flags.isEmpty()) {
                data.setFlags(flags.toArray(new String[0]));
            }
            if (hyperlanes != null) {
                data.setHyperlanes(hyperlanes);
            }
            return data;
        }
        return null;
    }

    private static String extractSystemName(String object) {
        Matcher nameMatcher = Pattern.compile("key=\"([^\"]+)\"").matcher(object);
        return nameMatcher.find() ? nameMatcher.group(1) : null;
    }

    private static int extractSystemId(String object) {
        Matcher idMatcher = Pattern.compile("(\\d+)=").matcher(object);
        return idMatcher.find() ? Integer.parseInt(idMatcher.group(1)) : -1;
    }

    private static double[] extractCoordinates(String object) {
        double[] coordinates = new double[2];
        Matcher xMatcher = Pattern.compile("x=(-?\\d+\\.?\\d*)").matcher(object);
        Matcher yMatcher = Pattern.compile("y=(-?\\d+\\.?\\d*)").matcher(object);
        if (xMatcher.find() && yMatcher.find()) {
            coordinates[0] = Double.parseDouble(xMatcher.group(1));
            coordinates[1] = Double.parseDouble(yMatcher.group(1));
        }
        return coordinates;
    }

    private static List<String> extractFlags(String object) {
        List<String> flags = new ArrayList<>();
        Matcher flagMatcher = Pattern.compile("flags=\\s*\\{([^}]*)\\}").matcher(object);
        if (flagMatcher.find()) {
            String flagsBlock = flagMatcher.group(1);
            Matcher individualFlagMatcher = Pattern.compile("([a-zA-Z0-9_]+)(?==)").matcher(flagsBlock);
            while (individualFlagMatcher.find()) {
                flags.add(individualFlagMatcher.group(1));
            }
        }
        return flags;
    }

    private static List<Hyperlane> extractHyperlanes(String object) {
        Matcher hyperlaneBlockMatcher = Pattern.compile("hyperlane=\\s*\\{\\s*((\\{[^{}]*\\}\\s*)+)\\}", Pattern.DOTALL).matcher(object);

        if (hyperlaneBlockMatcher.find()) {
            String hyperlaneBlock = hyperlaneBlockMatcher.group(1).trim();
            Pattern hyperlanePattern = Pattern.compile("to=(\\d+)");
            Matcher hyperlaneMatcher = hyperlanePattern.matcher(hyperlaneBlock);

            List<Hyperlane> hyperlanes = new ArrayList<>();

            while (hyperlaneMatcher.find()) {
                int toSystem = Integer.parseInt(hyperlaneMatcher.group(1));
                hyperlanes.add(new Hyperlane(toSystem));
            }

            return hyperlanes;
        }
        return null;
    }

    static class Hyperlane {
        int to;

        Hyperlane(int to) {
            this.to = to;
        }

        @Override
        public String toString() {
            return "Hyperlane to system " + to;
        }
    }

    static class SystemData {
        int id;
        String name;
        double x, y;
        String[] flags;
        List<Hyperlane> hyperlanes;

        SystemData(int id, String name, double x, double y) {
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
        }

        public void setFlags(String[] flags) {
            this.flags = flags;
        }

        public void setHyperlanes(List<Hyperlane> hyperlanes) {
            this.hyperlanes = hyperlanes;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder("System Name: " + name + ", Coordinates: (" + x + ", " + y + ")");
            if (flags != null && flags.length > 0) {
                result.append(", Flags: ").append(flags.length);
            }
            if (hyperlanes != null && !hyperlanes.isEmpty()) {
                result.append(", Hyperlanes: ").append(hyperlanes.size());
            }
            return result.toString().trim();
        }
    }
}