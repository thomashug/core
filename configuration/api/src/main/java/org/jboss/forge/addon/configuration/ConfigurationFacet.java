/*
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.jboss.forge.addon.configuration;

import org.jboss.forge.addon.projects.ProjectFacet;
import org.jboss.forge.addon.resource.FileResource;

/**
 * Returns the configuration for a given project
 * 
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 * 
 */
public interface ConfigurationFacet extends ProjectFacet
{
   /**
    * Returns the current configuration for this project.
    * 
    * @return null if this facet is not installed
    */
   public Configuration getConfiguration();

   /**
    * Returns the current configuration location for this project.
    */
   public FileResource<?> getConfigLocation();

}