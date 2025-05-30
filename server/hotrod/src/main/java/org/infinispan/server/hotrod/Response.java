package org.infinispan.server.hotrod;

import java.util.Map;

import org.infinispan.remoting.transport.Address;

abstract class AbstractTopologyResponse {
   final int topologyId;
   final Map<Address, ServerAddress> serverEndpointsMap;
   final int numSegments;

   protected AbstractTopologyResponse(int topologyId, Map<Address, ServerAddress> serverEndpointsMap, int numSegments) {
      this.topologyId = topologyId;
      this.serverEndpointsMap = serverEndpointsMap;
      this.numSegments = numSegments;
   }
}

class TopologyAwareResponse extends AbstractTopologyResponse {

   protected TopologyAwareResponse(int topologyId, Map<Address, ServerAddress> serverEndpointsMap, int numSegments) {
      super(topologyId, serverEndpointsMap, numSegments);
   }

   @Override
   public String toString() {
      return "TopologyAwareResponse{" +
             "topologyId=" + topologyId +
             ", numSegments=" + numSegments +
             ", addresses=" + serverEndpointsMap.keySet() +
             '}';
   }
}

class HashDistAware20Response extends AbstractTopologyResponse {
   final byte hashFunction;

   protected HashDistAware20Response(int topologyId, Map<Address, ServerAddress> serverEndpointsMap, int numSegments,
                                     byte hashFunction) {
      super(topologyId, serverEndpointsMap, numSegments);
      this.hashFunction = hashFunction;
   }

   @Override
   public String toString() {
      return "HashDistAware20Response{" +
             "topologyId=" + topologyId +
             ", numSegments=" + numSegments +
             ", hashFunction=" + hashFunction +
             ", addresses=" + serverEndpointsMap.keySet() +
             '}';
   }
}
