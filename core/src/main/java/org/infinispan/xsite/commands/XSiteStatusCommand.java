package org.infinispan.xsite.commands;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.infinispan.commands.remote.BaseRpcCommand;
import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoTypeId;
import org.infinispan.util.ByteString;
import org.infinispan.xsite.BackupSender;
import org.infinispan.xsite.status.TakeOfflineManager;

/**
 * Return the status of a {@link BackupSender}.
 *
 * @author Ryan Emerson
 * @since 11.0
 */
@Deprecated(since = "15.1", forRemoval = true)
@ProtoTypeId(ProtoStreamTypeIds.XSITE_STATUS_COMMAND)
public class XSiteStatusCommand extends BaseRpcCommand {

   public static final int COMMAND_ID = 100;

   @ProtoFactory
   public XSiteStatusCommand(ByteString cacheName) {
      super(cacheName);
   }

   @Override
   public CompletionStage<Map<String, Boolean>> invokeAsync(ComponentRegistry registry) throws Throwable {
      TakeOfflineManager takeOfflineManager = registry.getTakeOfflineManager().running();
      return CompletableFuture.completedFuture(takeOfflineManager.status());
   }

   @Override
   public final boolean isReturnValueExpected() {
      return true;
   }

   @Override
   public byte getCommandId() {
      return COMMAND_ID;
   }

   @Override
   public String toString() {
      return "XSiteStatusCommand{}";
   }
}
