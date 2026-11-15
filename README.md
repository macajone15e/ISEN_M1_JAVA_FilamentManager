# Gestionnaire de stock de filaments

Application console en français, Java 21 / Maven, pour gérer un catalogue configurable, des bobines et des projets d'impression 3D. Devise unique : EUR. Les exemples techniques sont **indicatifs** : suivre les recommandations du fabricant et les capacités de l'imprimante/séchoir.

## Installer, tester, lancer

Prérequis : JDK **21** et Maven **3.9+** dans le `PATH` (`java -version`, `mvn -version`). Aucun serveur ni compte externe.

```sh
mvn clean verify
java -jar target/filament-inventory-manager.jar
# Facultatif : choisir un répertoire de données
java -jar target/filament-inventory-manager.jar --data-dir ./mon-stock
java -jar target/filament-inventory-manager.jar --help
```

Le JAR contient ses dépendances Jackson. JUnit 5 n'est utilisé que pour les tests. Les sources sont encodées en UTF-8. Les rapports de tests se trouvent dans `target/surefire-reports`.

Au premier lancement, **si le dossier choisi n'existe pas**, les ressources `src/main/resources/examples/` sont chargées en mémoire. Le dossier n'est créé qu'à la première sauvegarde. Un dossier existant, même vide, doit contenir les quatre fichiers valides ; il n'est jamais réinitialisé automatiquement.

## Utilisation

Le menu principal propose :

| Menu | Actions |
|---|---|
| 1. Catalogue | Consulter, ajouter, modifier complètement les propriétés d'un matériau, sans changer son identifiant |
| 2. Inventaire | Lister, ajouter, rechercher par identifiant, filtrer par matériau/couleur/marque, ajuster après pesée |
| 3. Impressions | Consulter, créer, corriger un projet planifié, chercher les bobines compatibles, évaluer, terminer avec consommation réelle, annuler |
| 4. Séchage | Recommandations et dernière date connue, fin théorique, enregistrement d'un séchage terminé |
| 5. Statistiques | Poids et valeur globaux/par matériau, consommation et coût historiques, stocks faibles |
| 6. Sauvegarder | Sauvegarde explicite des quatre fichiers |
| 7. Quitter | Si nécessaire : sauvegarder, abandonner, ou revenir |

Une action de sous-menu ramène au menu principal ; `0` revient sans agir. Les nombres acceptent le point ou la virgule. Le diamètre proposé est **1,75 mm** (entrée vide). Les dates sont au format `AAAA-MM-JJ`, les dates/heures `AAAA-MM-JJTHH:MM` (heure locale de la machine). Les identifiants sont sensibles à la casse. Les saisies invalides ne modifient pas les données ; les erreurs de syntaxe numérique permettent une nouvelle saisie immédiate, les autres refus ramènent au menu pour recommencer. Une fin d'entrée n'enregistre **jamais** implicitement.

### Démonstration reproductible

Avec un dossier absent, par exemple `--data-dir ./demo-stock` :

1. **Inventaire → Ajouter** : identifiant `BTEST`, matériau `PLA`, marque `Exemple`, couleur `Rouge`, gamme vide, poids initial/restant `1000`, diamètre par défaut, prix `20.00`, achat `2024-01-01`.
2. **Impressions → Créer** : `PTEST`, nom `Support`, matériau `PLA`, diamètre par défaut, slicer `100`, buse `205`, plateau `55`.
3. **Impressions → Évaluer** : projet `PTEST`, bobine `BTEST`, estimateur par défaut : estimation `105 g`, coût `2.10 EUR`, faisable.
4. **Impressions → Terminer** : `PTEST`, `BTEST`, consommation **réelle** `80`, estimateur par défaut : stock `920 g`, coût réel `1.60 EUR`.
5. **Sauvegarder**, quitter puis relancer avec le même `--data-dir`.
6. Consulter le projet terminé et la bobine. Une seconde tentative de finalisation est refusée et ne déduit rien.

## Règles métier

- `Material` est concret : températures buse/plateau inclusives, température et durée de séchage, densité en g/cm³, perte en fraction `[0,1]`. Les six exemples PLA/PETG/TPU/ABS/ASA/PC sont définis **uniquement en JSON**. Durée et densité strictement positives ; valeurs finies et plages cohérentes.
- Le catalogue conserve une seule instance par identifiant. Une modification validée remplace atomiquement ses propriétés ; les bobines et les projets partagent cette référence. Les évaluations suivantes utilisent donc le catalogue courant. Un projet devenu incompatible reste consultable, corrigeable ou annulable ; son exécution est bloquée.
- Une bobine représente un achat : matériau, diamètre, capacité, prix et date d'achat ne changent pas. Marque/couleur obligatoires, gamme facultative. Le poids restant est dans `[0, capacité]` ; une consommation est strictement positive et ne dépasse pas le stock. Une pesée peut ajuster aux deux bornes, pas recharger au-delà. Achat et fin de séchage futurs interdits.
- Un projet utilise une seule bobine, de même identifiant matériau et **exactement** même diamètre nominal. Températures non négatives et dans les plages courantes pour exécuter. Statuts : `PLANNED`, `COMPLETED`, `CANCELLED`. Seul le premier autorise correction/finalisation/annulation.
- L'évaluation prévisionnelle ne réserve aucun stock. Standard = quantité slicer ; avec pertes (défaut) = `slicer × (1 + lossRate)`. Le menu choisit la stratégie pour chaque évaluation/finalisation. Les résultats non finis ou non positifs sont refusés.
- À l'exécution, **la quantité réelle fait foi**, même si l'estimation dépasse le stock. Statut, compatibilité, consommation et tous les résultats du bilan sont vérifiés/calculés avant mutation. Un refus ne change ni le stock ni le projet. Il n'y a pas d'étape d'E/S entre les deux mutations.
- Le bilan immuable conserve bobine, estimation, stratégie, consommation réelle, coûts, date d'exécution et nom historique du matériau. Une modification ultérieure du catalogue ne le recalcule pas. Les statistiques historiques sont issues des bilans, **pas** de `initialWeight - remainingWeight`, qui serait faux après une pesée.
- Séchage : calcul en heures depuis un début, avec franchissement de minuit ; enregistrement d'une fin non future. Aucune péremption ni pilotage de matériel n'est inventé. `Clock` est injecté dans les traitements temporels.
- Le seuil de stock faible vient de `configuration.json` (exemple : `100 g`) et la comparaison est **strictement inférieure**.

### Monnaie

Poids et paramètres techniques : `double`, sans `NaN` ni infini. Prix d'achat non négatif, `BigDecimal`, deux décimales maximum. Prix au gramme = prix/capacité avec **12 décimales, HALF_UP**. Les poids sont convertis par `BigDecimal.valueOf`. Coûts individuels arrondis à deux décimales après multiplication. La valeur totale du stock additionne les valeurs **non arrondies**, puis arrondit une seule fois. Un achat gratuit est accepté.

## Architecture et décisions justifiées

```text
com.filamentmanager
  Application             composition et démarrage
  model/                  objets métier, invariants et bilans
  operation/              StockOperation, StockConsumption, StockAdjustment
  estimator/              interface et deux stratégies
  service/                catalogue, inventaire, impressions, séchage, statistiques, session
  repository/             contrat de stockage, DTO et implémentation JSON
  exception/              erreurs métier explicites
  console/                saisie et affichage injectables
```

1. **Matériaux configurables plutôt qu'une hiérarchie PLA/PETG** : leurs différences sont des paramètres modifiables, pas des comportements distincts. Un nouvel identifiant ne nécessite aucun changement Java.
2. **Héritage des opérations de stock** : `StockOperation` impose validation puis application. `StockConsumption` et `StockAdjustment` ont des comportements réellement distincts. `InventoryService.executeOperation(StockOperation)` les traite polymorphiquement.
3. **Stratégie d'estimation** : `PrintService` dépend de `ConsumptionEstimator`. Standard et pertes sont substituables ; le service valide leurs résultats au lieu de faire confiance à l'implémentation.
4. **Métier séparé de la persistance** : aucune dépendance Jackson dans les modèles. Les DTO JSON portent uniquement les identifiants des références ; le chargement reconstruit le graphe partagé et valide l'agrégat avant de le publier. `StockRepository` est une frontière justifiée par les E/S et les tests.
5. **Calcul monétaire explicite** : `BigDecimal` et une politique d'arrondi documentée évitent les surprises du calcul binaire et de l'addition de valeurs déjà arrondies.
6. **Sauvegarde explicite** : l'utilisateur décide quand écrire. `ApplicationSession` suit les changements effectués via les services ; un échec d'écriture conserve les objets mémoire et l'indicateur non sauvegardé. Les clients de l'application doivent passer par ces services pour les mutations.

### SOLID sans abstractions artificielles

- **S** : les modèles protègent leurs invariants, les services orchestrent, la console adapte les E/S, le dépôt convertit et écrit.
- **O** : une autre stratégie d'estimation peut être injectée sans modifier `PrintService` ; les opérations ont un contrat commun.
- **L** : les deux opérations respectent « valider avant de modifier » ; les estimateurs respectent la même interface, leurs résultats sont contrôlés.
- **I** : `ConsumptionEstimator` n'exige qu'un calcul et `StockRepository` un chargement/une sauvegarde. Pas d'interface pour chaque classe.
- **D** : les dépendances externes sont injectées (`StockRepository`, `ConsumptionEstimator`, `Clock`, lecteurs/rédacteurs console). `Application` choisit les implémentations.

## Données et récupération

Le répertoire choisi contient :

- `materials.json` : liste des identifiants/propriétés ;
- `spools.json` : achats, poids restants et `materialId` ;
- `prints.json` : paramètres, statut et bilan facultatif ;
- `configuration.json` : seuil absolu de stock faible.

Les clés JSON et les statuts techniques (`PLANNED`, `COMPLETED`, `CANCELLED`) sont en anglais ; leur affichage dans la console reste en français. Ce format n’est pas compatible avec les anciens fichiers français : aucune migration automatique n’est effectuée. Conserver une copie des anciennes données avant toute conversion manuelle.

Le format exact est illustré par les ressources d'exemple. Ne modifier manuellement les fichiers qu'application arrêtée, après copie complète du dossier. Les champs inconnus, champs obligatoires absents, doublons (y compris clés JSON), références inconnues, statuts/bilans incohérents et valeurs invalides sont refusés. Les options facultatives `range`, `lastDrying`, `report` peuvent être absentes. Aucun type Java arbitraire n'est accepté depuis le JSON.

### Protocole de sauvegarde

1. Sérialiser l'agrégat et valider sa relecture avant d'écrire.
2. Écrire la nouvelle génération dans un répertoire temporaire sous le dossier de données.
3. Conserver les quatre fichiers précédents dans `.backup/` (à partir de la deuxième sauvegarde).
4. Poser `.transaction`, remplacer les quatre fichiers, puis retirer le marqueur.
5. Sur une erreur ordinaire d'écriture pendant le remplacement, restaurer les quatre fichiers précédents. Si la restauration échoue aussi, conserver le marqueur ; ne pas charger une génération mélangée.

Au redémarrage, la présence de `.transaction` **bloque le chargement et toute sauvegarde**. Après inspection et copie de sécurité du dossier, restaurer **ensemble** les quatre fichiers d'une `.backup/` complète, puis seulement supprimer `.transaction`. Sans copie complète (notamment interruption de la toute première sauvegarde), récupérer une sauvegarde externe ou choisir volontairement un **autre dossier absent** pour repartir avec les exemples. Ne pas supprimer le marqueur simplement pour contourner l'erreur.

Ce mécanisme ne prétend pas fournir une transaction multi-fichiers durable face à toute coupure système : pas de garantie `fsync`, de verrou multi-processus ni de récupération automatique. Une seule instance doit utiliser un dossier donné. Une sauvegarde externe reste nécessaire. Les fichiers invalides existants ne sont jamais écrasés automatiquement, même lors d'une sauvegarde demandée depuis une session déjà ouverte.

## Vérifications et correspondance TP

| Critère | Implémentation / tests |
|---|---|
| Encapsulation et invariants | Modèles, records de propriétés/paramètres/bilan ; `BusinessTest` |
| Héritage et polymorphisme | Deux sous-classes de `StockOperation`, testées via le type abstrait |
| Interface et stratégies | `ConsumptionEstimator`, standard/pertes, stratégies invalides testées |
| Collections et recherche | `Map` indexées, `Optional`, listes non modifiables, filtres |
| Calculs et compatibilité | Bornes, stock exact, monnaies, diamètre et températures inclusives |
| Cycle de vie et historique | Finalisation réelle distincte, annulation, correction, identité partagée, bilans inchangés |
| Dates testables | `Clock` fixe, séchage à minuit, dates futures refusées |
| Persistance et exceptions | `PersistenceTest` avec `@TempDir`, aller-retour, invalides, marqueur et pannes d'écriture injectées |
| Console robuste | `ConsoleTest`, E/S injectées, nombres invalides, parcours complet, relance, avertissement et sauvegarde en échec |
| Statistiques cohérentes | Service dédié ; historiques depuis les bilans, seuil strict, arrondi global |

Les tests n'écrivent jamais dans le dossier utilisateur par défaut. Ils couvrent également le refus d'une seconde finalisation après rechargement et l'absence de mutation après un refus.

## Limites assumées

Pas d'interface graphique/API, de réservation de stock, de partage d'un projet entre bobines, de recharge, de suppression du catalogue, de modification du prix/capacité d'un achat, de concurrence multi-utilisateur ou de commande d'un séchoir. Pas de publication ni de push automatique du dépôt.
