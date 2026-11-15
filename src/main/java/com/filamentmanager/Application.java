package com.filamentmanager;

import com.filamentmanager.console.ConsoleApplication;
import com.filamentmanager.estimator.LossAdjustedEstimator;
import com.filamentmanager.repository.JsonStockRepository;
import com.filamentmanager.service.ApplicationSession;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;

public final class Application {
    private Application() { }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage : java -jar filament-inventory-manager.jar [--data-dir <chemin>]");
            return;
        }
        boolean noArguments = args.length == 0;
        boolean directorySpecified = args.length == 2 && args[0].equals("--data-dir");
        if (!noArguments && !directorySpecified) {
            System.err.println("Arguments invalides. Usage : --data-dir <chemin>");
            System.exit(2);
            return;
        }
        try {
            Path directory;
            if (noArguments) {
                directory = Path.of("./data");
            } else {
                directory = Path.of(args[1]);
            }
            Clock clock = Clock.systemDefaultZone();
            JsonStockRepository repository = new JsonStockRepository(directory, clock);
            ApplicationSession session = new ApplicationSession(repository, new LossAdjustedEstimator(), clock);
            ConsoleApplication console = new ConsoleApplication(session,
                    new InputStreamReader(System.in, StandardCharsets.UTF_8),
                    new OutputStreamWriter(System.out, StandardCharsets.UTF_8), clock);
            console.start();
        } catch (IOException | InvalidPathException e) {
            System.err.println("Impossible de poursuivre : " + e.getMessage() + "\nAucune réinitialisation automatique des données.");
            System.exit(1);
        }
    }
}
