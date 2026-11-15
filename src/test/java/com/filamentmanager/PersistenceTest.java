package com.filamentmanager;

import com.filamentmanager.model.*;
import com.filamentmanager.repository.*;
import com.filamentmanager.service.*;
import com.filamentmanager.estimator.*;
import com.filamentmanager.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.filamentmanager.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    @TempDir
    Path root;

    Path directory() {
        return root.resolve("data");
    }

    JsonStockRepository repository() {
        return new JsonStockRepository(directory(), CLOCK);
    }

    ApplicationSession session() throws IOException {
        return new ApplicationSession(repository(), new LossAdjustedEstimator(), CLOCK);
    }

    void saveExamples() throws IOException {
        session().save();
    }

    void replace(String file, String previous, String newValue) throws IOException {
        Path p = directory().resolve(file);
        String text = Files.readString(p);

        assertTrue(text.contains(previous), previous);
        Files.writeString(p, text.replace(previous, newValue));
    }

    @Test
    void examplesOnlyForMissingDirectory() throws IOException {
        var s = session();

        assertTrue(s.modified());
        assertFalse(Files.exists(directory()));
        assertEquals(6, s.catalog().list().size());
        assertEquals(2, s.inventory().list().size());
        s.save();
        assertFalse(s.modified());
        assertFalse(repository().load().examples());
        var reloaded = session();

        assertFalse(reloaded.modified());
        Files.delete(directory().resolve("spools.json"));
        assertThrows(IOException.class, this::session);
        assertFalse(Files.exists(directory().resolve("spools.json")));
    }

    @Test
    void emptyDirectoryRejected() throws IOException {
        Files.createDirectory(directory());
        assertThrows(IOException.class, this::session);
        assertEquals(0, Objects.requireNonNull(directory().toFile().list()).length);
    }

    @Test
    void roundTripAndDoubleCompletion() throws IOException {
        var s = session();
        s.inventory().add(spool("BTEST", s.catalog().require("PLA"), 1000, 1000, "19.99", 1.75));
        s.prints().create(project("PTEST", s.catalog().require("PLA"), 100, 1.75, 205, 55));
        s.prints().complete("PTEST", "BTEST", 87);
        s.drying().record("BTEST", LocalDateTime.now(CLOCK));
        s.prints().cancel("P001");
        s.save();
        assertFalse(s.modified());
        var r = session();
        var b = r.inventory().require("BTEST");
        var p = r.prints().require("PTEST");

        assertEquals(913, b.remainingWeight());
        assertEquals(PrintProject.Status.COMPLETED, p.status());
        assertEquals(s.prints().require("PTEST").report(), p.report());
        assertEquals(LocalDateTime.now(CLOCK), b.lastDrying());
        assertSame(b.material(), p.parameters().material());
        assertSame(b.material(), r.catalog().require("PLA"));
        assertEquals(PrintProject.Status.CANCELLED, r.prints().require("P001").status());
        assertThrows(InvalidProjectStateException.class, () -> r.prints().complete("PTEST", "BTEST", 87));
        assertEquals(913, b.remainingWeight());
        r.catalog().update("PLA", properties("Nouveau", 210, 230, .2));
        r.save();
        assertEquals("PLA", session().prints().require("PTEST").report().materialLabel());
    }

    @Test
    void jsonUsesEnglishNamesWhileStatusDisplayStaysFrench() throws IOException {
        var session = session();
        session.prints().complete("P001", "B001", 30);
        session.save();

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var materials = mapper.readTree(directory().resolve("materials.json").toFile());
        var spools = mapper.readTree(directory().resolve("spools.json").toFile());
        var prints = mapper.readTree(directory().resolve("prints.json").toFile());
        var configuration = mapper.readTree(directory().resolve("configuration.json").toFile());

        assertTrue(materials.get(0).has("properties"));
        assertTrue(materials.get(0).get("properties").has("lossRate"));
        assertTrue(spools.get(0).has("materialId"));
        assertTrue(spools.get(0).has("remainingWeight"));
        assertTrue(configuration.has("lowStockThreshold"));
        assertEquals("COMPLETED", prints.get(0).get("status").asText());
        assertEquals("LossAdjustedEstimator", prints.get(0).get("report").get("estimator").asText());
        assertTrue(prints.get(0).get("report").has("actualConsumption"));
        assertFalse(Files.exists(directory().resolve("materiaux.json")));
        assertEquals("TERMINE", session.prints().require("P001").status().toString());
        assertEquals(PrintProject.Status.COMPLETED, session().prints().require("P001").status());
    }

    @Test
    void legacyFrenchFieldsAreRejectedWithoutOverwriting() throws IOException {
        saveExamples();
        Path configuration = directory().resolve("configuration.json");
        String legacy = "{\"seuilStockFaible\":100}";
        Files.writeString(configuration, legacy);
        assertThrows(IOException.class, this::session);
        assertThrows(IOException.class, () -> repository().save(new Business().data()));
        assertEquals(legacy, Files.readString(configuration));
    }

    @Test
    void previousGenerationPreserved() throws IOException {
        var s = session();
        s.save();
        byte[] previous = Files.readAllBytes(directory().resolve("spools.json"));
        s.inventory().executeOperation(new com.filamentmanager.operation.StockAdjustment("B001", 200));
        s.save();
        assertArrayEquals(previous, Files.readAllBytes(directory().resolve(".backup/spools.json")));
        assertEquals(200, session().inventory().require("B001").remainingWeight());
    }

    @Test
    void invalidJsonNeverReplaced() throws IOException {
        saveExamples();
        Path p = directory().resolve("materials.json");
        Files.writeString(p, "{invalide");
        assertThrows(IOException.class, this::session);
        assertEquals("{invalide", Files.readString(p));
        var state = new Business().data();

        assertThrows(IOException.class, () -> repository().save(state));
        assertEquals("{invalide", Files.readString(p));
    }

    @Test
    void duplicatesAndUnknownReferences() throws IOException {
        saveExamples();
        replace("spools.json", "B002", "B001");
        assertThrows(IOException.class, this::session);
        Files.writeString(directory().resolve("spools.json"), "[]");
        replace("prints.json", "\"PLA\"", "\"INCONNU\"");
        assertThrows(IOException.class, this::session);
    }

    @Test
    void missingNullFieldsAndInvalidValues() throws IOException {
        saveExamples();
        String original = Files.readString(directory().resolve("configuration.json"));
        for (String content : List.of(
                "{}",
                "{\"lowStockThreshold\":null}",
                "{\"lowStockThreshold\":-1}",
                "{\"lowStockThreshold\":\"100\"}",
                "{\"lowStockThreshold\":0,\"lowStockThreshold\":1}",
                "{\"lowStockThreshold\":0} {}",
                "null")) {
            Files.writeString(directory().resolve("configuration.json"), content);
            assertThrows(IOException.class, this::session, content);
        }
        Files.writeString(directory().resolve("configuration.json"), original);
        replace("materials.json", "\"lossRate\" : 0.05", "\"lossRate\" : null");
        assertThrows(IOException.class, this::session);
    }

    @Test
    void missingPropertyRejected() throws IOException {
        saveExamples();
        replace("materials.json", "\"bedMin\" : 0.0,", "");
        assertThrows(IOException.class, this::session);
    }

    @Test
    void inconsistentStatusAndFutureReport() throws IOException {
        var s = session();
        s.prints().complete("P001", "B001", 30);
        s.save();
        String initial = Files.readString(directory().resolve("prints.json"));
        replace("prints.json", "\"COMPLETED\"", "\"PLANNED\"");
        assertThrows(IOException.class, this::session);
        Files.writeString(directory().resolve("prints.json"), initial.replace("2025-05-10T12:00:00", "2099-05-10T12:00:00"));
        assertThrows(IOException.class, this::session);
        Files.writeString(directory().resolve("prints.json"), initial.replace("\"B001\"", "\"INCONNU\""));
        assertThrows(IOException.class, this::session);
        Files.writeString(directory().resolve("prints.json"), initial.replace("\"actualCost\" : 0.60", "\"actualCost\" : 0.61"));
        assertThrows(IOException.class, this::session);
    }

    @Test
    void interruptedTransactionBlocksEverything() throws IOException {
        saveExamples();
        Files.writeString(directory().resolve(JsonStockRepository.MARKER), "interrompu");
        IOException e = assertThrows(IOException.class, this::session);
        assertTrue(e.getMessage().contains("QUATRE"));
        assertThrows(IOException.class, () -> repository().save(new Business().data()));
        assertTrue(Files.exists(directory().resolve(JsonStockRepository.MARKER)));
    }

    @ParameterizedTest
    // Writes 1–4 stage files, 5–8 back them up, 9 marks the transaction, 10–13 replace files.
    @ValueSource(ints = {1, 5, 9, 10, 12, 13})
    void writeFailureRestoresFilesAndKeepsMemory(int position) throws IOException {
        saveExamples();
        Map<String, byte[]> before = new HashMap<>();
        for (String file : JsonStockRepository.FILES) {
            before.put(file, Files.readAllBytes(directory().resolve(file)));
        }
        AtomicInteger calls = new AtomicInteger();
        var failing = new JsonStockRepository(directory(), CLOCK, (p, content) -> {
            if (calls.incrementAndGet() == position) {
                throw new IOException("Panne simulée");
            }
            Files.write(p, content);
        });
        var s = new ApplicationSession(failing, new LossAdjustedEstimator(), CLOCK);
        s.prints().complete("P001", "B001", 40);
        assertThrows(IOException.class, s::save);
        assertTrue(s.modified());
        assertEquals(760, s.inventory().require("B001").remainingWeight());
        for (String file : JsonStockRepository.FILES) {
            assertArrayEquals(before.get(file), Files.readAllBytes(directory().resolve(file)));
        }
        assertFalse(Files.exists(directory().resolve(JsonStockRepository.MARKER)));
        assertEquals(800, session().inventory().require("B001").remainingWeight());
        s.save();
        assertFalse(s.modified());
        assertEquals(760, session().inventory().require("B001").remainingWeight());
    }

    @Test
    void firstSaveFailureAllowsRetry() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        var r = new JsonStockRepository(directory(), CLOCK, (p, content) -> {
            if (calls.incrementAndGet() == 7) {
                throw new IOException("Panne");
            }
            Files.write(p, content);
        });
        var s = new ApplicationSession(r, new LossAdjustedEstimator(), CLOCK);

        assertThrows(IOException.class, s::save);
        assertTrue(s.modified());
        assertFalse(Files.exists(directory()));
        s.save();
        assertFalse(s.modified());
        assertFalse(repository().load().examples());
    }
}
