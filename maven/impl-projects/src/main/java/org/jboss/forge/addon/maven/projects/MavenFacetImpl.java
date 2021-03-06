/*
 * Copyright 2012 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jboss.forge.addon.maven.projects;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.cli.MavenCli;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Repository;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import org.jboss.forge.addon.environment.Environment;
import org.jboss.forge.addon.facets.AbstractFacet;
import org.jboss.forge.addon.maven.environment.Network;
import org.jboss.forge.addon.maven.projects.util.NativeSystemCall;
import org.jboss.forge.addon.maven.projects.util.RepositoryUtils;
import org.jboss.forge.addon.maven.resources.MavenModelResource;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.ProjectFacet;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.resource.ResourceFactory;
import org.jboss.forge.addon.resource.events.ResourceEvent;
import org.jboss.forge.addon.resource.monitor.ResourceListener;
import org.jboss.forge.addon.resource.monitor.ResourceMonitor;
import org.jboss.forge.furnace.container.cdi.events.Local;
import org.jboss.forge.furnace.event.PreShutdown;
import org.jboss.forge.furnace.manager.maven.MavenContainer;
import org.jboss.forge.furnace.spi.ListenerRegistration;
import org.jboss.forge.furnace.util.OperatingSystemUtils;

/**
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
public class MavenFacetImpl extends AbstractFacet<Project> implements ProjectFacet, MavenFacet
{
   private static final Logger log = Logger.getLogger(MavenFacetImpl.class.getName());

   private ProjectBuildingResult buildingResult;
   private ProjectBuilder builder;
   private ResourceMonitor monitor;
   private ListenerRegistration<ResourceListener> listenerRegistration;

   @Inject
   private MavenContainer container;

   @Inject
   private Environment environment;

   @Inject
   private ResourceFactory factory;

   @Inject
   private PlexusContainer plexus;

   private ProjectBuilder getBuilder()
   {
      if (builder == null)
         builder = plexus.lookup(ProjectBuilder.class);
      return builder;
   }

   public ProjectBuildingRequest getRequest()
   {
      return getBuildingRequest(Network.isOffline(environment));
   }

   public ProjectBuildingRequest getOfflineRequest()
   {
      return getBuildingRequest(true);
   }

   public ProjectBuildingRequest getBuildingRequest(final boolean offline)
   {
      ClassLoader cl = Thread.currentThread().getContextClassLoader();
      try
      {
         Settings settings = container.getSettings();
         // TODO this needs to be configurable via .forge
         // TODO this reference to the M2_REPO should probably be centralized

         MavenExecutionRequest executionRequest = new DefaultMavenExecutionRequest();
         MavenExecutionRequestPopulator populator = plexus.lookup(MavenExecutionRequestPopulator.class);
         populator.populateFromSettings(executionRequest, container.getSettings());
         populator.populateDefaults(executionRequest);
         RepositorySystem system = plexus.lookup(RepositorySystem.class);
         ProjectBuildingRequest request = executionRequest.getProjectBuildingRequest();

         ArtifactRepository localRepository = RepositoryUtils.toArtifactRepository("local",
                  new File(settings.getLocalRepository()).toURI().toURL().toString(), null, true, true);
         request.setLocalRepository(localRepository);

         List<ArtifactRepository> settingsRepos = new ArrayList<>(request.getRemoteRepositories());
         List<String> activeProfiles = settings.getActiveProfiles();

         Map<String, Profile> profiles = settings.getProfilesAsMap();

         for (String id : activeProfiles)
         {
            Profile profile = profiles.get(id);
            if (profile != null)
            {
               List<Repository> repositories = profile.getRepositories();
               for (Repository repository : repositories)
               {
                  settingsRepos.add(RepositoryUtils.convertFromMavenSettingsRepository(repository));
               }
            }
         }
         request.setRemoteRepositories(settingsRepos);
         request.setSystemProperties(System.getProperties());

         DefaultRepositorySystemSession repositorySession = MavenRepositorySystemUtils.newSession();
         Proxy activeProxy = settings.getActiveProxy();
         if (activeProxy != null)
         {
            DefaultProxySelector dps = new DefaultProxySelector();
            dps.add(RepositoryUtils.convertFromMavenProxy(activeProxy), activeProxy.getNonProxyHosts());
            repositorySession.setProxySelector(dps);
         }
         LocalRepository localRepo = new LocalRepository(settings.getLocalRepository());
         repositorySession.setLocalRepositoryManager(system.newLocalRepositoryManager(repositorySession, localRepo));
         repositorySession.setOffline(offline);
         List<Mirror> mirrors = executionRequest.getMirrors();
         if (mirrors != null)
         {
            DefaultMirrorSelector mirrorSelector = new DefaultMirrorSelector();
            for (Mirror mirror : mirrors)
            {
               mirrorSelector.add(mirror.getId(), mirror.getUrl(), mirror.getLayout(), false, mirror.getMirrorOf(),
                        mirror.getMirrorOfLayouts());
            }
            repositorySession.setMirrorSelector(mirrorSelector);
         }

         request.setRepositorySession(repositorySession);
         request.setProcessPlugins(false);
         request.setResolveDependencies(false);
         return request;
      }
      catch (RuntimeException e)
      {
         throw e;
      }
      catch (Exception e)
      {
         throw new RuntimeException(
                  "Could not create Maven project building request", e);
      }
      finally
      {
         /*
          * We reset the classloader to prevent potential modules bugs if Classwords container changes classloaders on
          * us
          */
         Thread.currentThread().setContextClassLoader(cl);
      }
   }

   @Override
   public void setFaceted(Project project)
   {
      super.setFaceted(project);
   }

   @Override
   public boolean install()
   {
      if (!isInstalled())
      {
         MavenModelResource pom = getModelResource();
         if (!pom.createNewFile())
            throw new IllegalStateException("Could not create POM file.");
         pom.setContents(createDefaultPOM());
         monitor = pom.monitor();
         listenerRegistration = monitor.addResourceListener(new ResourceListener()
         {
            @Override
            public void processEvent(ResourceEvent event)
            {
               invalidateBuildingResults();
            }
         });
      }
      return isInstalled();
   }

   public void onShutdown(@Observes @Local PreShutdown event)
   {
      if (listenerRegistration != null)
         listenerRegistration.removeListener();
   }

   @Override
   public boolean uninstall()
   {
      if (monitor != null)
      {
         monitor.cancel();
      }
      return super.uninstall();
   }

   private String createDefaultPOM()
   {
      MavenXpp3Writer writer = new MavenXpp3Writer();
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      org.apache.maven.project.MavenProject mavenProject = new org.apache.maven.project.MavenProject();
      mavenProject.setModelVersion("4.0.0");
      try
      {
         writer.write(baos, mavenProject.getModel());
         return baos.toString();
      }
      catch (IOException e)
      {
         // Should not happen
         throw new RuntimeException("Failed to create default pom.xml", e);
      }
   }

   @Override
   public MavenModelResource getModelResource()
   {
      return getFaceted().getRootDirectory().getChild("pom.xml").reify(MavenModelResource.class);
   }

   @Override
   public boolean isInstalled()
   {
      MavenModelResource pom = getModelResource();
      return pom != null && pom.exists();
   }

   @Override
   public Model getModel()
   {
      return getModelResource().getCurrentModel();
   }

   @Override
   public void setModel(final Model pom)
   {
      MavenXpp3Writer writer = new MavenXpp3Writer();
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      Writer fw = new OutputStreamWriter(outputStream);
      try
      {
         writer.write(fw, pom);
         getModelResource().setContents(outputStream.toString());
      }
      catch (IOException e)
      {
         throw new RuntimeException("Could not write POM file: " + getModelResource().getFullyQualifiedName(), e);
      }
      finally
      {
         try
         {
            fw.close();
            outputStream.close();
         }
         catch (IOException e)
         {
            throw new RuntimeException(e);
         }
      }

      /*
       * Invalidate build result immediately; otherwise, the current thread may not get correct results until the
       * monitor thread catches the change.
       */
      invalidateBuildingResults();
   }

   /*
    * POM manipulation methods
    */
   public synchronized ProjectBuildingResult getProjectBuildingResult() throws Exception
   {
      if (this.buildingResult == null)
      {
         ProjectBuildingRequest request = null;
         request = getRequest();
         MavenModelResource pomResource = getModelResource();
         if (request != null)
         {
            try
            {
               request.setResolveDependencies(true);
               // FORGE-1287
               // buildingResult = getBuilder().build(new FileResourceModelSource(pomResource), request);
               buildingResult = getBuilder().build(pomResource.getUnderlyingResourceObject(), request);
            }
            catch (RuntimeException full)
            {
               throw full;
            }
            catch (Exception full)
            {
               throw new RuntimeException(full);
            }
         }
         else
         {
            throw new RuntimeException("Project building request was null");
         }
      }
      return buildingResult;
   }

   private void invalidateBuildingResults()
   {
      this.buildingResult = null;
   }

   @Override
   public Map<String, String> getProperties()
   {
      Map<String, String> result = new HashMap<>();

      try
      {
         Properties properties = getProjectBuildingResult().getProject().getProperties();
         for (Entry<Object, Object> o : properties.entrySet())
         {
            result.put((String) o.getKey(), (String) o.getValue());
         }
      }
      catch (Exception e)
      {
         log.log(Level.WARNING, "Failed to resolve properties in [" + getModelResource().getFullyQualifiedName() + "].");
         log.log(Level.FINE, "Failed to resolve properties in Project [" + getModelResource().getFullyQualifiedName()
                  + "].", e);
      }

      return result;
   }

   @Override
   public String resolveProperties(String input)
   {
      String result = input;
      try
      {
         if (input != null)
         {
            Properties properties = getProjectBuildingResult().getProject().getProperties();

            for (Entry<Object, Object> e : properties.entrySet())
            {
               String key = "\\$\\{" + e.getKey().toString() + "\\}";
               Object value = e.getValue();
               result = result.replaceAll(key, value.toString());
            }
         }
      }
      catch (Exception e)
      {
         log.log(Level.WARNING, "Failed to resolve properties in [" + getModelResource().getFullyQualifiedName()
                  + "] for input value [" + input + "].");
         log.log(Level.FINE, "Failed to resolve properties in Project [" + getModelResource().getFullyQualifiedName()
                  + "].", e);
      }

      return result;
   }

   @Override
   public boolean executeMavenEmbedded(final List<String> parameters)
   {
      return executeMavenEmbedded(parameters.toArray(new String[parameters.size()]));
   }

   public boolean executeMavenEmbedded(final String[] parms)
   {
      return executeMavenEmbedded(System.out, System.err, parms);
   }

   @Override
   public boolean executeMavenEmbedded(List<String> parameters, PrintStream out, PrintStream err)
   {
      return executeMavenEmbedded(out, err, parameters.toArray(new String[parameters.size()]));
   }

   public boolean executeMavenEmbedded(final PrintStream out, final PrintStream err, String[] parms)
   {
      if ((parms == null) || (parms.length == 0))
      {
         parms = new String[] { "" };
      }
      MavenCli cli = new MavenCli();
      int i = cli.doMain(parms, getFaceted().getRootDirectory().getFullyQualifiedName(),
               out, err);
      return i == 0;
   }

   @Override
   public boolean executeMaven(final List<String> parameters)
   {
      return executeMaven(parameters.toArray(new String[parameters.size()]));
   }

   public boolean executeMaven(final String[] selected)
   {
      // return executeMaven(new NullOutputStream(), selected);
      return executeMaven(System.out, selected);
   }

   public boolean executeMaven(final OutputStream out, final String[] parms)
   {
      try
      {
         int returnValue = NativeSystemCall.execFromPath(getMvnCommand(), parms, out, getFaceted().getRootDirectory());
         if (returnValue == 0)
            return true;
         else
            return executeMavenEmbedded(parms);
      }
      catch (IOException e)
      {
         return executeMavenEmbedded(parms);
      }
   }

   private String getMvnCommand()
   {
      return OperatingSystemUtils.isWindows() ? "mvn.bat" : "mvn";
   }

   @Override
   public DirectoryResource getLocalRepositoryDirectory()
   {
      return factory.create(new File(container.getSettings().getLocalRepository())).reify(
               DirectoryResource.class);
   }

   @Override
   public boolean isModelValid()
   {
      try
      {
         getProjectBuildingResult();
         return true;
      }
      catch (Exception e)
      {
         return false;
      }
   }

}
