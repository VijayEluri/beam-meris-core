/*
 * $Id: DpmConfig.java,v 1.1 2007/03/27 12:51:41 marcoz Exp $
 *
 * Copyright (c) 2003 Brockmann Consult GmbH. All right reserved.
 * http://www.brockmann-consult.de
 */
package org.esa.beam.meris.l2auxdata;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.util.Date;
import java.util.Iterator;

import org.esa.beam.util.ResourceInstaller;
import org.esa.beam.util.SystemUtils;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import com.bc.ceres.core.NullProgressMonitor;

/**
 * Represents the configuration for the MERIS Level 2 Processor.
 */
public class DpmConfig {

    private static final String SYMBOLIC_NAME = "beam-meris-l2auxdata";
    private static final String AUXDATA_DIRNAME = "meris_l2";
    private static final String MERIS_L2_CONF = "meris_l2_config.xml";
//    private static final String MERIS_L2_CONF = "meris_l2_config_new.xml";
    
    private Element _rootElement;
    private File auxdataTargetDir;

    /**
     * Constructs a new configuration.
     *
     * @throws L2AuxDataException
     *          if the configuration could not be loaded from the file
     */
    public DpmConfig() throws L2AuxDataException {
        String auxdataSrcPath = "auxdata/" + AUXDATA_DIRNAME;
        final String auxdataDestPath = ".beam/" + SYMBOLIC_NAME + "/" + auxdataSrcPath;
        auxdataTargetDir = new File(SystemUtils.getUserHomeDir(), auxdataDestPath);
        URL sourceUrl = ResourceInstaller.getSourceUrl(this.getClass());

        ResourceInstaller resourceInstaller = new ResourceInstaller(sourceUrl, auxdataSrcPath, auxdataTargetDir);
        
        try {
            resourceInstaller.install(".*", new NullProgressMonitor());
        } catch (IOException e) {
            throw new L2AuxDataException("Could not install " + auxdataSrcPath, e);
        }
        File configFile = new File(auxdataTargetDir, MERIS_L2_CONF);
        FileReader reader = null;
        try {
            reader = new FileReader(configFile);
            init(reader);
        } catch (FileNotFoundException e) {
            throw new L2AuxDataException("Configuration file not found: " + configFile.getPath());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * Gets the directory of the MERIS Level 2 auxiliary databases.
     *
     * @return the auxiliary databases directory, never <code>null</code>
     * @throws L2AuxDataException if the directory could not be retrieved from this configuration
     */
    public File getAuxDataDir() throws L2AuxDataException {
        final Element auxDataConfigElement = getMandatoryChild(_rootElement, "aux_data_config");
        final String auxDataDirPath = getOptionalAttribute(auxDataConfigElement, "dir");
        final File auxDataDir;
        if (auxDataDirPath != null) {
            auxDataDir = new File(auxDataDirPath);
        } else {
            auxDataDir = auxdataTargetDir;
        }
        return auxDataDir;
    }

    // todo - check if really in use or just tested

    /**
     * Gets the file path for the given database.
     *
     * @param name           the database name, e.g. <code>"landaero"</code> or <code>"case2"</code>
     * @param aquisitionDate the aquisition date of a given level 1b input product, can be <code>null</code>
     * @return the file path to the database file, never <code>null</code>
     * @throws L2AuxDataException if the file could not be retrieved from this configuration
     */
    public File getAuxDatabaseFile(String name, Date aquisitionDate) throws L2AuxDataException {
        String auxDatabaseFilename = null;
        final Element auxDataConfigElement = getMandatoryChild(_rootElement, "aux_data_config");
        final Element auxDataDefaultsElement = getMandatoryChild(auxDataConfigElement, "aux_data_defaults");
        final Iterator auxDatabaseElementIt = getMandatoryChildren(auxDataDefaultsElement, "aux_database");
        while (auxDatabaseElementIt.hasNext()) {
            Element auxDatabaseElement = (Element) auxDatabaseElementIt.next();
            final String auxDatabaseName = getMandatoryAttribute(auxDatabaseElement, "name");
            if (name.equalsIgnoreCase(auxDatabaseName)) {
                auxDatabaseFilename = getMandatoryAttribute(auxDatabaseElement, "file");
                break;
            }
        }

        if (aquisitionDate != null) {
            // todo - loop through "aux_data_overrides" children and compare given dates with "aquisition_start" and "aquisition_end"
        }

        if (auxDatabaseFilename == null) {
            throw new L2AuxDataException("Auxiliary database name not specified in configuration: " + name);
        }

        File auxDataDir = getAuxDataDir();
        return new File(new File(auxDataDir, name), auxDatabaseFilename);
    }

    /**
     * Gets the file path for the given database.
     *
     * @param fileInfo       the file information
     * @param aquisitionDate the aquisition date of a given level 1b input product, can be <code>null</code>
     * @return the file path to the database file, never <code>null</code>
     * @throws L2AuxDataException if the file could not be retrieved from this configuration
     */
    public AuxFile getAuxFile(AuxFileInfo fileInfo, Date aquisitionDate) throws L2AuxDataException {
        final Element auxDataConfigElement = getMandatoryChild(_rootElement, "aux_data_config");
        final Element auxDataDefaultsElement = getMandatoryChild(auxDataConfigElement, "aux_data_defaults");
        final Iterator auxDatabaseElementIt = getMandatoryChildren(auxDataDefaultsElement, "aux_database");
        final String dirName = fileInfo.getDirName();
        String filename = null;
        while (auxDatabaseElementIt.hasNext()) {
            Element auxDatabaseElement = (Element) auxDatabaseElementIt.next();
            final String auxDatabaseName = getMandatoryAttribute(auxDatabaseElement, "name");
            if (dirName.equalsIgnoreCase(auxDatabaseName)) {
                filename = getMandatoryAttribute(auxDatabaseElement, "file");
                break;
            }
        }
        if (aquisitionDate != null) {
            // todo - loop through "aux_data_overrides" children and compare given dates with "aquisition_start" and "aquisition_end"
        }
        if (filename == null) {
            throw new L2AuxDataException("Missing filename for auxiliary file type '" + dirName + "'");
        }
        final File dir = new File(getAuxDataDir(), dirName);
        final File file = new File(dir, filename);
        return new AuxFile(fileInfo, file);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Private implementation helpers

    private Element getMandatoryChild(Element parent, String name) throws L2AuxDataException {
        final Element child = parent.getChild(name);
        if (child == null) {
            throw new L2AuxDataException("Missing element '" + name + "' in element '" + parent.getName() + "'");
        }
        return child;
    }

    private Iterator getMandatoryChildren(Element parent, String name) throws L2AuxDataException {
        final Iterator iterator = (parent.getChildren(name)).iterator();
        if (!iterator.hasNext()) {
            throw new L2AuxDataException("Missing element(s) '" + name + "' in element '" + parent.getName() + "'");
        }
        return iterator;
    }


    private String getOptionalAttribute(Element element, String name) {
        return element.getAttributeValue(name);
    }

    private String getMandatoryAttribute(Element element, String name) throws L2AuxDataException {
        final String value = element.getAttributeValue(name);
        if (value == null) {
            throw new L2AuxDataException("Missing attribute '" + name + "' in element '" + element.getName() + "'");
        }
        return value;
    }


    private void init(Reader reader) throws L2AuxDataException {
        final SAXBuilder saxBuilder = new SAXBuilder();
        saxBuilder.setValidation(false); // todo - provide XML schema or DTD for config
        try {
            final Document document = saxBuilder.build(reader);
            _rootElement = document.getRootElement();
        } catch (JDOMException e) {
            throw new L2AuxDataException("Failed to load configuration", e);
        } catch (IOException e) {
            throw new L2AuxDataException("Failed to load configuration", e);
        }
    }
}
