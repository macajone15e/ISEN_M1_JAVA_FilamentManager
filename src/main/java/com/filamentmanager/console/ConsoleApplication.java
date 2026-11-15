package com.filamentmanager.console;

import com.filamentmanager.model.*;
import com.filamentmanager.service.*;
import com.filamentmanager.operation.StockAdjustment;
import com.filamentmanager.estimator.*;
import com.filamentmanager.exception.InvalidDataException;

import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Input/output adapter only: business decisions remain in the services. */
public final class ConsoleApplication {
    private final ApplicationSession session;
    private final BufferedReader input;
    private final PrintWriter output;
    private final Clock clock;

    private static final class EndOfInput extends RuntimeException { }

    public ConsoleApplication(ApplicationSession session, Reader input, Writer output, Clock clock) {
        this.session = session;
        this.input = new BufferedReader(input);
        this.output = new PrintWriter(output, true);
        this.clock = clock;
    }

    public void start() throws IOException {
        output.println("Gestionnaire de filaments — EUR, grammes, °C et mm");
        if (session.modified()) {
            output.println("Exemples chargés en mémoire : sauvegarde explicite nécessaire.");
        }
        try {
            boolean running = true;
            while (running) {
                output.println("\n1. Catalogue  2. Inventaire  3. Impressions  4. Séchage  5. Statistiques  6. Sauvegarder  7. Quitter");
                try {
                    switch (read("Choix")) {
                        case "1" -> catalog();
                        case "2" -> inventory();
                        case "3" -> prints();
                        case "4" -> drying();
                        case "5" -> statistics();
                        case "6" -> save();
                        case "7" -> running = !quit();
                        default -> output.println("Choix invalide, réessayez.");
                    }
                } catch (InvalidDataException | DateTimeParseException e) {
                    output.println("Refus : " + e.getMessage() + ". Vous pouvez réessayer depuis le menu.");
                }
            }
        } catch (EndOfInput e) {
            String message = "Fin d'entrée : aucune sauvegarde implicite.";
            if (session.modified()) {
                message += " Modifications non sauvegardées.";
            }
            output.println(message);
        }
    }

    private String read(String prompt) throws IOException {
        output.print(prompt + " > ");
        output.flush();
        String line = input.readLine();
        if (line == null) {
            throw new EndOfInput();
        }
        return line.strip();
    }

    private String text(String prompt) throws IOException {
        while (true) {
            String inputText = read(prompt);
            if (!inputText.isBlank()) {
                return inputText;
            }
            output.println("Champ obligatoire, réessayez.");
        }
    }

    private double number(String prompt) throws IOException {
        while (true) {
            try {
                double value = Double.parseDouble(read(prompt).replace(',', '.'));
                if (Double.isFinite(value)) {
                    return value;
                }
            } catch (NumberFormatException e) {
                // Invalid input must not interrupt the menu.
            }
            output.println("Nombre fini attendu, réessayez.");
        }
    }

    private double diameter() throws IOException {
        while (true) {
            String inputText = read("Diamètre en mm [1.75]");
            if (inputText.isEmpty()) {
                return 1.75;
            }
            try {
                double value = Double.parseDouble(inputText.replace(',', '.'));
                return Validation.positive(value, "Diamètre");
            } catch (NumberFormatException | InvalidDataException e) {
                output.println("Diamètre positif fini attendu.");
            }
        }
    }

    private BigDecimal price() throws IOException {
        while (true) {
            try {
                BigDecimal amount = new BigDecimal(read("Prix d'achat EUR").replace(',', '.'));
                return Validation.amount(amount, "Prix");
            } catch (NumberFormatException | InvalidDataException e) {
                output.println("Montant non négatif à deux décimales maximum attendu.");
            }
        }
    }

    private void display(List<?> values) {
        if (values.isEmpty()) {
            output.println("Aucun résultat.");
        } else {
            for (Object value : values) {
                output.println(value);
            }
        }
    }

    private Material material() throws IOException {
        String identifier = text("Identifiant matériau");
        return session.catalog().require(identifier);
    }

    private Material.Properties properties() throws IOException {
        String name = text("Nom matériau");
        double nozzleMin = number("Buse min °C");
        double nozzleMax = number("Buse max °C");
        double bedMin = number("Plateau min °C");
        double bedMax = number("Plateau max °C");
        double dryingTemperature = number("Séchage °C");
        double dryingDuration = number("Durée séchage h");
        double density = number("Densité g/cm³");
        double lossRate = number("Taux de perte en fraction [0..1]");
        return new Material.Properties(name, nozzleMin, nozzleMax, bedMin, bedMax,
                dryingTemperature, dryingDuration, density, lossRate);
    }

    private void catalog() throws IOException {
        output.println("Catalogue : 1. Consulter  2. Ajouter  3. Modifier complètement  0. Retour");
        switch (read("Action")) {
            case "1" -> display(session.catalog().list());
            case "2" -> {
                String identifier = text("Nouvel identifiant");
                Material newValue = new Material(identifier, properties());
                session.catalog().add(newValue);
            }
            case "3" -> {
                String identifier = text("Identifiant à modifier");
                session.catalog().require(identifier);
                session.catalog().update(identifier, properties());
                output.println("Matériau modifié. Faisabilité recalculée avec les nouvelles propriétés :");
                for (PrintProject project : session.prints().list()) {
                    boolean planned = project.status() == PrintProject.Status.PLANNED;
                    boolean affectedMaterial = project.parameters().material().id().equals(identifier);
                    if (planned && affectedMaterial) {
                        output.println(project.id() + " :");
                        for (Spool spool : session.inventory().list()) {
                            PrintService.Evaluation evaluation = session.prints().evaluate(project.id(), spool.id());
                            output.println("  " + spool.id() + " " + evaluation);
                        }
                    }
                }
            }
            case "0" -> { }
            default -> output.println("Action invalide.");
        }
    }

    private void inventory() throws IOException {
        output.println("Inventaire : 1. Lister  2. Ajouter  3. Rechercher  4. Filtrer  5. Ajuster après pesée  0. Retour");
        switch (read("Action")) {
            case "1" -> display(session.inventory().list());
            case "2" -> {
                String identifier = text("Identifiant bobine");
                Material material = material();
                String brand = text("Marque");
                String color = text("Couleur");
                String range = read("Gamme facultative");
                double initialWeight = number("Poids initial g");
                double remainingWeight = number("Poids restant g");
                double diameter = diameter();
                BigDecimal price = price();
                LocalDate purchaseDate = LocalDate.parse(text("Date achat AAAA-MM-JJ"));
                Spool spool = new Spool(identifier, material, brand, color, range,
                        initialWeight, remainingWeight, diameter, price, purchaseDate, null, clock);
                session.inventory().add(spool);
            }
            case "3" -> {
                String identifier = text("Identifiant bobine");
                Optional<Spool> result = session.inventory().find(identifier);
                if (result.isPresent()) {
                    output.println(result.get().toString());
                } else {
                    output.println("Bobine introuvable.");
                }
            }
            case "4" -> {
                String materialId = read("Matériau (vide = tous)");
                String color = read("Couleur (vide = toutes)");
                String brand = read("Marque (vide = toutes)");
                List<Spool> result = new ArrayList<>();
                for (Spool spool : session.inventory().list()) {
                    boolean materialMatches = materialId.isEmpty() || spool.material().id().equals(materialId);
                    boolean colorMatches = color.isEmpty() || spool.color().equalsIgnoreCase(color);
                    boolean brandMatches = brand.isEmpty() || spool.brand().equalsIgnoreCase(brand);
                    if (materialMatches && colorMatches && brandMatches) {
                        result.add(spool);
                    }
                }
                display(result);
            }
            case "5" -> {
                String identifier = text("Identifiant bobine");
                double measuredWeight = number("Poids mesuré g");
                session.inventory().executeOperation(new StockAdjustment(identifier, measuredWeight));
            }
            case "0" -> { }
            default -> output.println("Action invalide.");
        }
    }

    private PrintProject.Parameters parameters() throws IOException {
        String name = text("Nom projet");
        Material material = material();
        double diameter = diameter();
        double slicerWeight = number("Poids slicer g");
        double nozzleTemperature = number("Buse °C");
        double bedTemperature = number("Plateau °C");
        return new PrintProject.Parameters(name, material, diameter, slicerWeight, nozzleTemperature, bedTemperature);
    }

    private ConsumptionEstimator estimator() throws IOException {
        while (true) {
            switch (read("Estimateur : 1 standard, 2 avec pertes [défaut]")) {
                case "1" -> {
                    return new StandardEstimator();
                }
                case "", "2" -> {
                    return new LossAdjustedEstimator();
                }
                default -> output.println("Choix invalide.");
            }
        }
    }

    private void prints() throws IOException {
        output.println("Impressions : 1. Consulter  2. Créer  3. Modifier  4. Évaluer et sélectionner  5. Terminer  6. Annuler  0. Retour");
        switch (read("Action")) {
            case "1" -> display(session.prints().list());
            case "2" -> {
                String identifier = text("Identifiant projet");
                PrintProject project = new PrintProject(identifier, parameters());
                session.prints().create(project);
            }
            case "3" -> {
                String identifier = text("Identifiant projet");
                session.prints().require(identifier).checkPlanned();
                session.prints().update(identifier, parameters());
            }
            case "4" -> {
                String projectId = text("Identifiant projet");
                output.println("Bobines techniquement compatibles (stock non réservé) :");
                display(session.prints().compatibleSpools(projectId));
                String spoolId = text("Bobine à évaluer");
                output.println(session.prints().evaluate(projectId, spoolId, estimator()));
            }
            case "5" -> {
                String projectId = text("Identifiant projet");
                String spoolId = text("Bobine sélectionnée");
                double actualConsumption = number("Consommation RÉELLE g");
                session.prints().complete(projectId, spoolId, actualConsumption, estimator());
                output.println("Impression terminée : " + session.prints().require(projectId).report());
            }
            case "6" -> session.prints().cancel(text("Identifiant projet"));
            case "0" -> { }
            default -> output.println("Action invalide.");
        }
    }

    private void drying() throws IOException {
        output.println("Séchage : 1. Recommandations / dernière date  2. Fin théorique  3. Enregistrer une fin passée  0. Retour");
        switch (read("Action")) {
            case "1" -> output.println(session.drying().recommendations(text("Identifiant bobine")));
            case "2" -> {
                String spoolId = text("Identifiant bobine");
                LocalDateTime start = LocalDateTime.parse(text("Début AAAA-MM-JJTHH:MM"));
                output.println(session.drying().calculateEnd(spoolId, start));
            }
            case "3" -> {
                String spoolId = text("Identifiant bobine");
                LocalDateTime end = LocalDateTime.parse(text("Fin AAAA-MM-JJTHH:MM"));
                session.drying().record(spoolId, end);
            }
            case "0" -> { }
            default -> output.println("Action invalide.");
        }
    }

    private void statistics() {
        StatisticsService.Summary total = session.statistics().total();
        output.println("Disponible : " + total.availableWeight() + " g ; valeur : " + total.remainingValue()
                + " EUR ; impressions terminées : " + total.actualConsumption() + " g, " + total.actualCost() + " EUR");
        for (StatisticsService.ByMaterial summary : session.statistics().byMaterial()) {
            output.println(summary);
        }
        output.println("Stocks strictement inférieurs à " + session.configuration().lowStockThreshold() + " g :");
        display(session.inventory().lowStock(session.configuration().lowStockThreshold()));
    }

    private boolean save() {
        try {
            session.save();
            output.println("Sauvegarde effectuée.");
            return true;
        } catch (IOException e) {
            output.println("Sauvegarde impossible : " + e.getMessage());
            return false;
        }
    }

    private boolean quit() throws IOException {
        if (!session.modified()) {
            return true;
        }
        while (true) {
            output.println("Modifications non sauvegardées : s = sauvegarder et quitter, a = abandonner, r = revenir");
            switch (read("Choix")) {
                case "s" -> {
                    return save();
                }
                case "a" -> {
                    return true;
                }
                case "r" -> {
                    return false;
                }
                default -> output.println("Choix invalide.");
            }
        }
    }
}
