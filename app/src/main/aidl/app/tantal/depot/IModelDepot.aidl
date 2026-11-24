package app.tantal.depot;

import app.tantal.depot.ModelAccess;

interface IModelDepot {
    String declareRequired(String purpose, in List<String> packages);
    ModelAccess openModel(String packageVariant);
    String reportModel(String packageVariant, String digest, boolean success);
    String getStatus(String packageVariant);
}
