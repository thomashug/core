/**
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jboss.forge.addon.shell;

import java.io.File;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.annotation.PreDestroy;
import javax.enterprise.inject.Vetoed;

import org.jboss.aesh.console.AeshConsole;
import org.jboss.aesh.console.AeshConsoleBuilder;
import org.jboss.aesh.console.Console;
import org.jboss.aesh.console.Prompt;
import org.jboss.aesh.console.export.ExportManager;
import org.jboss.aesh.console.helper.InterruptHook;
import org.jboss.aesh.console.settings.Settings;
import org.jboss.aesh.console.settings.SettingsBuilder;
import org.jboss.aesh.terminal.CharacterType;
import org.jboss.aesh.terminal.Color;
import org.jboss.aesh.terminal.TerminalCharacter;
import org.jboss.aesh.terminal.TerminalColor;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.resource.FileResource;
import org.jboss.forge.addon.resource.Resource;
import org.jboss.forge.addon.shell.aesh.ForgeCommandNotFoundHandler;
import org.jboss.forge.addon.shell.aesh.ForgeCommandRegistry;
import org.jboss.forge.addon.shell.ui.ShellContextImpl;
import org.jboss.forge.addon.shell.ui.ShellUIOutputImpl;
import org.jboss.forge.addon.shell.ui.ShellUIPromptImpl;
import org.jboss.forge.addon.ui.UIRuntime;
import org.jboss.forge.addon.ui.command.CommandExecutionListener;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIContextListener;
import org.jboss.forge.addon.ui.controller.CommandControllerFactory;
import org.jboss.forge.addon.ui.input.UIPrompt;
import org.jboss.forge.addon.ui.output.UIOutput;
import org.jboss.forge.addon.ui.progress.DefaultUIProgressMonitor;
import org.jboss.forge.addon.ui.progress.UIProgressMonitor;
import org.jboss.forge.furnace.addons.AddonRegistry;
import org.jboss.forge.furnace.services.Imported;
import org.jboss.forge.furnace.spi.ListenerRegistration;
import org.jboss.forge.furnace.util.Assert;
import org.jboss.forge.furnace.util.OperatingSystemUtils;

/**
 * Implementation of the {@link Shell} interface.
 * 
 * @author <a href="ggastald@redhat.com">George Gastaldi</a>
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
@Vetoed
public class ShellImpl implements Shell, UIRuntime
{
   private Resource<?> currentResource;

   private final AddonRegistry addonRegistry;
   private final AeshConsole console;
   private final UIOutput output;
   private final List<CommandExecutionListener> executionListeners = new LinkedList<>();

   public ShellImpl(FileResource<?> initialResource, Settings settings,
            CommandManager commandManager, AddonRegistry addonRegistry, CommandControllerFactory commandFactory)
   {
      this.currentResource = initialResource;
      this.addonRegistry = addonRegistry;
      // Set the paths for the Aesh history, alias and export files.
      File forgeHome = OperatingSystemUtils.getUserForgeDir();
      File history = new File(forgeHome, "history");
      File alias = new File(forgeHome, "alias");
      File export = new File(forgeHome, "export");
      Settings newSettings = new SettingsBuilder(settings)
               .historyFile(history)
               .aliasFile(alias)
               .exportFile(export)
               .interruptHook(new InterruptHook()
               {
                  @Override
                  public void handleInterrupt(Console console)
                  {
                     console.getShell().out().println("^C");
                     console.clearBufferAndDisplayPrompt();
                  }
               }).create();
      final ForgeCommandRegistry registry = new ForgeCommandRegistry(this, commandManager, commandFactory,
               commandManager
                        .getConverterFactory());
      this.console = new AeshConsoleBuilder()
               .prompt(createPrompt())
               .settings(newSettings)
               .commandRegistry(registry)
               .commandNotFoundHandler(new ForgeCommandNotFoundHandler(registry))
               .create();
      this.output = new ShellUIOutputImpl(console);
      this.console.start();
   }

   private void updatePrompt()
   {
      console.setPrompt(createPrompt());
   }

   /**
    * Creates an initial prompt
    */
   private Prompt createPrompt()
   {
      // [ currentdir]$
      if (OperatingSystemUtils.isWindows())
      {
         List<TerminalCharacter> prompt = new LinkedList<>();
         prompt.add(new TerminalCharacter('['));
         for (char c : currentResource.getName().toCharArray())
         {
            prompt.add(new TerminalCharacter(c));
         }
         prompt.add(new TerminalCharacter(']'));
         prompt.add(new TerminalCharacter('$'));
         prompt.add(new TerminalCharacter(' '));
         return new Prompt(prompt);
      }
      else
      {
         List<TerminalCharacter> prompt = new LinkedList<>();
         prompt.add(new TerminalCharacter('[', new TerminalColor(Color.BLUE, Color.DEFAULT),
                  CharacterType.BOLD));
         for (char c : currentResource.getName().toCharArray())
         {
            prompt.add(new TerminalCharacter(c));
         }
         prompt.add(new TerminalCharacter(']', new TerminalColor(Color.BLUE, Color.DEFAULT),
                  CharacterType.BOLD));
         prompt.add(new TerminalCharacter('$'));
         prompt.add(new TerminalCharacter(' '));
         return new Prompt(prompt);
      }
   }

   @PreDestroy
   @Override
   public void close()
   {
      this.console.stop();
   }

   @Override
   public Resource<?> getCurrentResource()
   {
      return currentResource;
   }

   @Override
   public void setCurrentResource(final Resource<?> resource)
   {
      Assert.notNull(resource, "Current resource should not be null");
      this.currentResource = resource;

      Resource<?> temp = resource;
      while (!(temp instanceof DirectoryResource) && temp != null)
      {
         temp = temp.getParent();
      }
      if (temp instanceof DirectoryResource)
      {
         console.getAeshContext().setCurrentWorkingDirectory(((DirectoryResource) temp).getUnderlyingResourceObject());
      }
      updatePrompt();
   }

   @Override
   public boolean isGUI()
   {
      return false;
   }

   @Override
   public AeshConsole getConsole()
   {
      return console;
   }

   @Override
   public UIOutput getOutput()
   {
      return output;
   }

   public ShellContextImpl createUIContext()
   {
      Imported<UIContextListener> listeners = addonRegistry.getServices(UIContextListener.class);
      ShellContextImpl shellContextImpl = new ShellContextImpl(this, currentResource, listeners);
      for (CommandExecutionListener listener : executionListeners)
      {
         shellContextImpl.addCommandExecutionListener(listener);
      }
      ExportManager exportManager = console.getExportManager();
      if (exportManager != null)
      {
         Map<Object, Object> attributeMap = shellContextImpl.getAttributeMap();
         for (String variableName : exportManager.getAllNames())
         {
            // Variable name ends with "=". Aesh Bug?
            if (variableName.endsWith("="))
            {
               variableName = variableName.split("=")[0];
            }
            String variableValue = exportManager.getValue(variableName);
            attributeMap.put(variableName, variableValue);
         }
      }
      return shellContextImpl;
   }

   @Override
   public ListenerRegistration<CommandExecutionListener> addCommandExecutionListener(
            final CommandExecutionListener listener)
   {
      executionListeners.add(listener);
      return new ListenerRegistration<CommandExecutionListener>()
      {
         @Override
         public CommandExecutionListener removeListener()
         {
            executionListeners.remove(listener);
            return listener;
         }
      };
   }

   @Override
   public UIProgressMonitor createProgressMonitor(UIContext context)
   {
      return new DefaultUIProgressMonitor();
   }

   @Override
   public UIPrompt createPrompt(UIContext context)
   {
      return new ShellUIPromptImpl(context, console);
   }
}
