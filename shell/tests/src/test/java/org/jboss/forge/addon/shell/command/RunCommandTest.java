/**
 * Copyright 2014 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.jboss.forge.addon.shell.command;

import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.resource.FileResource;
import org.jboss.forge.addon.resource.Resource;
import org.jboss.forge.addon.resource.ResourceFactory;
import org.jboss.forge.addon.shell.mock.command.ThrowExceptionCommand;
import org.jboss.forge.addon.shell.test.ShellTest;
import org.jboss.forge.addon.ui.result.Failed;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.arquillian.AddonDependency;
import org.jboss.forge.arquillian.Dependencies;
import org.jboss.forge.arquillian.archive.ForgeArchive;
import org.jboss.forge.furnace.repositories.AddonDependencyEntry;
import org.jboss.forge.furnace.util.OperatingSystemUtils;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
@RunWith(Arquillian.class)
public class RunCommandTest
{

   @Deployment
   @Dependencies({
            @AddonDependency(name = "org.jboss.forge.addon:maven"),
            @AddonDependency(name = "org.jboss.forge.addon:ui"),
            @AddonDependency(name = "org.jboss.forge.addon:shell-test-harness"),
            @AddonDependency(name = "org.jboss.forge.addon:resources"),
            @AddonDependency(name = "org.jboss.forge.furnace.container:cdi")
   })
   public static ForgeArchive getDeployment()
   {
      ForgeArchive archive = ShrinkWrap
               .create(ForgeArchive.class)
               .addClasses(ThrowExceptionCommand.class)
               .addBeansXML()
               .addAsAddonDependencies(
                        AddonDependencyEntry.create("org.jboss.forge.addon:maven"),
                        AddonDependencyEntry.create("org.jboss.forge.addon:ui"),
                        AddonDependencyEntry.create("org.jboss.forge.addon:shell-test-harness"),
                        AddonDependencyEntry.create("org.jboss.forge.addon:resources"),
                        AddonDependencyEntry.create("org.jboss.forge.furnace.container:cdi")
               );

      return archive;
   }

   private static final int COMMAND_TIMEOUT = 5000;

   @Inject
   private ShellTest shellTest;

   @Inject
   private ResourceFactory resourceFactory;

   @Test
   public void testRunScriptSingleLine() throws Exception
   {
      DirectoryResource temp = (DirectoryResource) resourceFactory.create(OperatingSystemUtils.createTempDir());
      temp.deleteOnExit();
      shellTest.getShell().setCurrentResource(temp);

      FileResource<?> script = (FileResource<?>) temp.getChild("script.fsh");
      script.setContents("touch foo.txt");

      Resource<?> child = temp.getChild("foo.txt");
      Assert.assertFalse(child.exists());

      Result result = shellTest.execute("run script.fsh", COMMAND_TIMEOUT, TimeUnit.SECONDS);
      Assert.assertFalse(result instanceof Failed);

      Assert.assertTrue(child.exists());
      child.delete();
   }

   @Test
   public void testRunScriptMultiLine() throws Exception
   {
      DirectoryResource temp = (DirectoryResource) resourceFactory.create(OperatingSystemUtils.createTempDir());
      temp.deleteOnExit();
      shellTest.getShell().setCurrentResource(temp);

      FileResource<?> script = (FileResource<?>) temp.getChild("script.fsh");
      script.setContents("touch foo.txt\n"
               + "touch foo2.txt");

      Resource<?> child = temp.getChild("foo.txt");
      Resource<?> child2 = temp.getChild("foo2.txt");
      Assert.assertFalse(child.exists());
      Assert.assertFalse(child2.exists());

      Result result = shellTest.execute("run script.fsh", COMMAND_TIMEOUT, TimeUnit.SECONDS);
      Assert.assertFalse(result instanceof Failed);

      Assert.assertTrue(child.exists());
      Assert.assertTrue(child2.exists());
      child.delete();
      child2.delete();
   }

   @Test
   public void testRunScriptFailure() throws Exception
   {
      DirectoryResource temp = (DirectoryResource) resourceFactory.create(OperatingSystemUtils.createTempDir());
      temp.deleteOnExit();
      shellTest.getShell().setCurrentResource(temp);

      FileResource<?> script = (FileResource<?>) temp.getChild("script.fsh");
      script.setContents("throw-exception");

      Result result = shellTest.execute("run script.fsh", COMMAND_TIMEOUT, TimeUnit.SECONDS);
      Assert.assertTrue(result instanceof Failed);
   }
}
