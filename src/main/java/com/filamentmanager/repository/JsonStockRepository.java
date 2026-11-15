package com.filamentmanager.repository;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.filamentmanager.exception.InvalidDataException;
import com.filamentmanager.model.*;
import com.filamentmanager.service.CatalogService;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.filamentmanager.repository.PersistenceDto.*;

public final class JsonStockRepository implements StockRepository {
    public static final List<String> FILES = List.of("materials.json", "spools.json", "prints.json", "configuration.json");
    public static final String MARKER = ".transaction";
    public static final String BACKUP = ".backup";

    @FunctionalInterface
    public interface FileWriter {
        void write(Path path, byte[] content) throws IOException;
    }

    private final Path directory;
    private final Clock clock;
    private final FileWriter fileWriter;
    // Reject ambiguous or coerced input rather than silently repairing user data.
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public JsonStockRepository(Path directory, Clock clock) {
        this(directory, clock, (path, content) -> Files.write(path, content));
    }

    public JsonStockRepository(Path directory, Clock clock, FileWriter fileWriter) {
        this.directory = directory.toAbsolutePath().normalize();
        this.clock = clock;
        this.fileWriter = fileWriter;
    }

    private void checkTransaction() throws IOException {
        if (Files.exists(directory.resolve(MARKER))) {
            throw new IOException("Sauvegarde interrompue dans " + directory
                    + ". Ne rien écraser. Restaurer les QUATRE fichiers de " + directory.resolve(BACKUP)
                    + " puis supprimer " + MARKER + ". Sans sauvegarde complète, récupérer une copie externe.");
        }
    }

    @Override
    public LoadResult load() throws IOException {
        checkTransaction();
        // An existing but incomplete directory is an error, not a reason to load examples.
        boolean examples = !Files.exists(directory);
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (String name : FILES) {
            if (examples) {
                try (InputStream resource = getClass().getResourceAsStream("/examples/" + name)) {
                    if (resource == null) {
                        throw new IOException("Ressource exemple absente : " + name);
                    }
                    contents.put(name, resource.readAllBytes());
                }
            } else {
                Path file = directory.resolve(name);
                if (!Files.isRegularFile(file)) {
                    throw new IOException("Dossier incomplet, fichier requis : " + file);
                }
                contents.put(name, Files.readAllBytes(file));
            }
        }
        return new LoadResult(decode(contents), examples);
    }

    private <T> T read(Map<String, byte[]> contents, String file, Class<T> type) throws IOException {
        try {
            T data = mapper.readValue(contents.get(file), type);
            return Validation.required(data, file);
        } catch (IOException | InvalidDataException e) {
            throw new IOException("Données invalides : " + directory.resolve(file) + " : " + e.getMessage(), e);
        }
    }

    private ApplicationData decode(Map<String, byte[]> contents) throws IOException {
        try {
            List<Material> materials = new ArrayList<>();
            for (MaterialDto dto : read(contents, "materials.json", MaterialDto[].class)) {
                Validation.required(dto, "Matériau");
                try {
                    PropertiesDto propertiesDto = Validation.required(dto.properties(), "Propriétés");
                    Material.Properties properties = new Material.Properties(propertiesDto.name(),
                            required(propertiesDto.nozzleMin()), required(propertiesDto.nozzleMax()),
                            required(propertiesDto.bedMin()), required(propertiesDto.bedMax()),
                            required(propertiesDto.dryingTemperature()), required(propertiesDto.dryingDuration()),
                            required(propertiesDto.density()), required(propertiesDto.lossRate()));
                    materials.add(new Material(dto.id(), properties));
                } catch (InvalidDataException e) {
                    throw new InvalidDataException("Matériau " + dto.id() + " : " + e.getMessage(), e);
                }
            }

            // Resolve every material ID to the same catalog object so updates stay shared.
            // Loading does not count as a change to report to the session.
            CatalogService catalog = new CatalogService(materials, () -> { });
            List<Spool> spools = new ArrayList<>();
            for (SpoolDto dto : read(contents, "spools.json", SpoolDto[].class)) {
                Validation.required(dto, "Bobine");
                try {
                    Spool spool = new Spool(dto.id(), catalog.require(dto.materialId()), dto.brand(),
                            dto.color(), dto.range(), dto.initialWeight(), dto.remainingWeight(), dto.diameter(),
                            dto.price(), dto.purchaseDate(), dto.lastDrying(), clock);
                    spools.add(spool);
                } catch (InvalidDataException e) {
                    throw new InvalidDataException("Bobine " + dto.id() + " : " + e.getMessage(), e);
                }
            }

            List<PrintProject> projects = new ArrayList<>();
            for (ProjectDto dto : read(contents, "prints.json", ProjectDto[].class)) {
                Validation.required(dto, "Projet");
                ParametersDto parametersDto = Validation.required(dto.parameters(), "Paramètres " + dto.id());
                PrintProject.Parameters parameters = new PrintProject.Parameters(parametersDto.name(),
                        catalog.require(parametersDto.materialId()), parametersDto.diameter(), parametersDto.slicerWeight(),
                        parametersDto.nozzleTemperature(), parametersDto.bedTemperature());
                PrintProject project = new PrintProject(dto.id(), parameters);
                PrintProject.Status status = Validation.required(dto.status(), "Statut " + dto.id());
                if (status == PrintProject.Status.COMPLETED) {
                    ReportDto reportDto = Validation.required(dto.report(), "Bilan " + dto.id());
                    LocalDateTime executionDate = Validation.required(reportDto.executionDate(), "Date exécution");
                    if (executionDate.isAfter(LocalDateTime.now(clock))) {
                        throw new InvalidDataException("Date d'exécution future : " + dto.id());
                    }
                    PrintProject.Report report = new PrintProject.Report(reportDto.spoolId(),
                            reportDto.actualConsumption(), reportDto.estimation(), reportDto.estimator(),
                            reportDto.estimatedCost(), reportDto.actualCost(), reportDto.executionDate(), reportDto.materialLabel());
                    project.complete(report);
                } else {
                    if (dto.report() != null) {
                        throw new InvalidDataException("Bilan inattendu : " + dto.id());
                    }
                    if (status == PrintProject.Status.CANCELLED) {
                        project.cancel();
                    }
                }
                projects.add(project);
            }

            ConfigurationDto configuration = read(contents, "configuration.json", ConfigurationDto.class);
            return new ApplicationData(materials, spools, projects, new Configuration(configuration.lowStockThreshold()));
        } catch (InvalidDataException e) {
            throw new IOException("Données invalides dans " + directory + " : " + e.getMessage(), e);
        }
    }

    private static double required(Double value) {
        return Validation.required(value, "Propriété numérique matériau");
    }

    private Map<String, byte[]> encode(ApplicationData data) throws IOException {
        List<MaterialDto> materials = new ArrayList<>();
        for (Material material : data.materials()) {
            Material.Properties properties = material.properties();
            PropertiesDto propertiesDto = new PropertiesDto(properties.name(), properties.nozzleMin(), properties.nozzleMax(),
                    properties.bedMin(), properties.bedMax(), properties.dryingTemperature(),
                    properties.dryingDuration(), properties.density(), properties.lossRate());
            materials.add(new MaterialDto(material.id(), propertiesDto));
        }

        List<SpoolDto> spools = new ArrayList<>();
        for (Spool spool : data.spools()) {
            SpoolDto dto = new SpoolDto(spool.id(), spool.material().id(), spool.brand(), spool.color(),
                    spool.range(), spool.initialWeight(), spool.remainingWeight(), spool.diameter(), spool.price(),
                    spool.purchaseDate(), spool.lastDrying());
            spools.add(dto);
        }

        List<ProjectDto> projects = new ArrayList<>();
        for (PrintProject project : data.projects()) {
            PrintProject.Parameters parameters = project.parameters();
            PrintProject.Report report = project.report();
            ReportDto reportDto = null;
            if (report != null) {
                reportDto = new ReportDto(report.spoolId(), report.actualConsumption(), report.estimation(), report.estimator(),
                        report.estimatedCost(), report.actualCost(), report.executionDate(), report.materialLabel());
            }
            ParametersDto parametersDto = new ParametersDto(parameters.name(), parameters.material().id(), parameters.diameter(),
                    parameters.slicerWeight(), parameters.nozzleTemperature(), parameters.bedTemperature());
            projects.add(new ProjectDto(project.id(), parametersDto, project.status(), reportDto));
        }

        Map<String, byte[]> contents = new LinkedHashMap<>();
        contents.put("materials.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(materials));
        contents.put("spools.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(spools));
        contents.put("prints.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(projects));
        contents.put("configuration.json", mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(new ConfigurationDto(data.configuration().lowStockThreshold())));

        // Validate the complete generation before writing anything to disk.
        decode(contents);
        return contents;
    }

    @Override
    public void save(ApplicationData data) throws IOException {
        checkTransaction();
        Map<String, byte[]> contents = encode(data);
        boolean newDirectory = !Files.exists(directory);
        Map<String, byte[]> previousContents = new LinkedHashMap<>();

        if (!newDirectory) {
            // Never replace an already invalid or incomplete data set.
            load();
            for (String name : FILES) {
                previousContents.put(name, Files.readAllBytes(directory.resolve(name)));
            }
        }

        Files.createDirectories(directory);
        Path temporary = Files.createTempDirectory(directory, ".generation-");
        boolean transaction = false;
        try {
            for (String name : FILES) {
                fileWriter.write(temporary.resolve(name), contents.get(name));
            }
            if (!previousContents.isEmpty()) {
                Path backup = directory.resolve(BACKUP);
                Files.createDirectories(backup);
                for (String name : FILES) {
                    fileWriter.write(backup.resolve(name), previousContents.get(name));
                }
            }

            // Four replacements are not atomic together; the marker prevents loading a mixed generation.
            transaction = true;
            fileWriter.write(directory.resolve(MARKER),
                    "Save in progress\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String name : FILES) {
                // Copy through the abstraction to allow simulated failures during replacement too.
                fileWriter.write(directory.resolve(name), Files.readAllBytes(temporary.resolve(name)));
            }
            Files.delete(directory.resolve(MARKER));
        } catch (IOException e) {
            if (transaction) {
                try {
                    for (String name : FILES) {
                        if (previousContents.containsKey(name)) {
                            Files.write(directory.resolve(name), previousContents.get(name));
                        } else {
                            Files.deleteIfExists(directory.resolve(name));
                        }
                    }
                    Files.deleteIfExists(directory.resolve(MARKER));
                } catch (IOException restoration) {
                    // Leave the marker in place when rollback fails; automatic loading is unsafe.
                    e.addSuppressed(restoration);
                }
            }
            throw new IOException("Échec de sauvegarde dans " + directory + ". État mémoire conservé. " + e.getMessage(), e);
        } finally {
            for (String name : FILES) {
                Files.deleteIfExists(temporary.resolve(name));
            }
            Files.deleteIfExists(temporary);
            if (newDirectory && !Files.exists(directory.resolve(FILES.getFirst())) && !Files.exists(directory.resolve(MARKER))) {
                Files.deleteIfExists(directory);
            }
        }
    }
}
