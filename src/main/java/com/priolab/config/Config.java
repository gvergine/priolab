package com.priolab.config;

/**
 * Persistent application configuration, stored as {@code ~/.priolab/config.json}.
 */
public class Config {

    private int version = 1;

    /** Absolute path to the directory that holds connector subdirectories. */
    private String connectorsDir;

    /** Absolute path to the directory that holds exporter subdirectories. */
    private String exportersDir;

    /** The project file open when PrioLab last exited; reopened on startup. */
    private String lastProjectFile;

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getConnectorsDir() {
        return connectorsDir;
    }

    public void setConnectorsDir(String connectorsDir) {
        this.connectorsDir = connectorsDir;
    }

    public String getExportersDir() {
        return exportersDir;
    }

    public void setExportersDir(String exportersDir) {
        this.exportersDir = exportersDir;
    }

    public String getLastProjectFile() {
        return lastProjectFile;
    }

    public void setLastProjectFile(String lastProjectFile) {
        this.lastProjectFile = lastProjectFile;
    }
}
