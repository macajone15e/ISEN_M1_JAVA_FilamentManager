package com.filamentmanager;

import com.filamentmanager.console.ConsoleApplication;
import com.filamentmanager.estimator.LossAdjustedEstimator;
import com.filamentmanager.repository.*;
import com.filamentmanager.service.ApplicationSession;
import com.filamentmanager.model.Spool;
import com.filamentmanager.model.Material;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;

import static com.filamentmanager.Fixtures.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

class ConsoleTest {
    @TempDir
    Path root;

    Path directory() {
        return root.resolve("data");
    }

    ApplicationSession session() throws IOException {
        return new ApplicationSession(new JsonStockRepository(directory(), CLOCK), new LossAdjustedEstimator(), CLOCK);
    }

    String execute(ApplicationSession s, String inputs) throws IOException {
        StringWriter output = new StringWriter();
        new ConsoleApplication(s, new StringReader(inputs), output, CLOCK).start();
        return output.toString();
    }

    @Test
    void invalidInputAndDiscardWarning() throws IOException {
        String text = execute(session(), "invalide\n7\nr\n7\na\n");

        assertTrue(text.contains("Choix invalide"));
        assertTrue(text.contains("Modifications non sauvegardées"));
        assertFalse(Files.exists(directory()));
    }

    @Test
    void endOfInputDoesNotSave() throws IOException {
        String text = execute(session(), "");

        assertTrue(text.contains("aucune sauvegarde implicite"));
        assertFalse(Files.exists(directory()));
    }

    @Test
    void normalFlowWithInvalidNumberAndRestart() throws IOException {
        String inputs = String.join("\n",
                "2", "2", "BTEST", "PLA", "Marque", "Rouge", "", "oops", "NaN", "1000", "1000", "", "20.00", "2024-01-01",
                "3", "2", "PTEST", "Support", "PLA", "", "100", "205", "55",
                "3", "4", "PTEST", "BTEST", "",
                "3", "5", "PTEST", "BTEST", "80", "",
                "6", "7", "");
        String text = execute(session(), inputs);

        assertTrue(text.contains("Nombre fini attendu"));
        assertTrue(text.contains("faisable=true"));
        assertTrue(text.contains("Impression terminée"));
        assertTrue(text.contains("Sauvegarde effectuée"));
        var reloaded = session();

        assertEquals(920, reloaded.inventory().require("BTEST").remainingWeight());
        String second = execute(reloaded, "3\n5\nPTEST\nBTEST\n80\n\n7\n");

        assertTrue(second.contains("statut TERMINE"));
        assertEquals(920, reloaded.inventory().require("BTEST").remainingWeight());
    }

    @Test
    void domainDetailsAreDisplayedInFrench() throws IOException {
        var session = session();
        String output = execute(session, "1\n1\n3\n1\n3\n4\nP001\nB001\n\n3\n5\nP001\nB001\n30\n\n5\n7\na\n");

        assertTrue(output.contains("Propriétés[nom="));
        assertTrue(output.contains("Paramètres[nom="));
        assertTrue(output.contains("PLANIFIE"));
        assertTrue(output.contains("Évaluation[compatible="));
        assertTrue(output.contains("faisable=true"));
        assertTrue(output.contains("consommation réelle=30.0"));
        assertTrue(output.contains("estimateur=avec pertes"));
        assertTrue(output.contains("Par matériau[matériau="));
        assertFalse(output.contains("remainingWeight="));
        assertFalse(output.contains("PLANNED"));
        assertFalse(output.contains("LossAdjustedEstimator"));
    }

    @Test
    void saveRequestedOnQuit() throws IOException {
        String text = execute(session(), "7\ns\n");

        assertTrue(text.contains("Sauvegarde effectuée"));
        assertTrue(Files.exists(directory().resolve("materials.json")));
    }

    @Test
    void searchDisplaysSpoolOrNotFoundMessage() throws IOException {
        ApplicationSession session = session();
        Material material = session.catalog().require("PLA");
        Spool spool = Fixtures.spool("BLECTURE", material, 100, 100, "1.00", 1.75);
        session.inventory().add(spool);

        String present = execute(session, "2\n3\nBLECTURE\n7\na\n");
        String absent = execute(session, "2\n3\nINCONNUE\n7\na\n");

        assertTrue(present.contains(spool.toString()));
        assertTrue(absent.contains("Bobine introuvable."));
    }

    @Test
    void filteringAcceptsEmptyCriteriaAndIgnoresLabelCase() throws IOException {
        ApplicationSession session = session();
        Material pla = session.catalog().require("PLA");
        Material otherMaterial = new Material("AUTRE", pla.properties());
        session.catalog().add(otherMaterial);
        session.inventory().add(Fixtures.spool("BFILTRE", pla, 100, 100, "1.00", 1.75));
        session.inventory().add(Fixtures.spool("BEXCLU", otherMaterial, 100, 100, "1.00", 1.75));

        String filter = execute(session, "2\n4\nPLA\nbLeU\nmArQuE\n7\na\n");
        String all = execute(session, "2\n4\n\n\n\n7\na\n");
        String none = execute(session, "2\n4\nPLA\nCouleur absente\n\n7\na\n");
        String wrongBrand = execute(session, "2\n4\nPLA\nBleu\nMarque absente\n7\na\n");

        assertTrue(filter.contains("BFILTRE —"));
        assertFalse(filter.contains("BEXCLU —"));
        assertTrue(all.contains("BFILTRE —"));
        assertTrue(all.contains("BEXCLU —"));
        assertTrue(none.contains("Aucun résultat."));
        assertTrue(wrongBrand.contains("Aucun résultat."));
    }

    @Test
    void saveFailureDoesNotCloseConsole() throws IOException {
        var repository = new JsonStockRepository(directory(), CLOCK, (p, b) -> {
            throw new IOException("Disque plein");
        });
        var s = new ApplicationSession(repository, new LossAdjustedEstimator(), CLOCK);
        String text = execute(s, "7\ns\n5\n7\na\n");

        assertTrue(text.contains("Sauvegarde impossible"));
        assertTrue(text.contains("Disponible :"));
        assertTrue(s.modified());
    }
}
