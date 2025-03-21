package org.infinispan.commons.util;

/**
 * Provides general information about the processors on this host.
 *
 * @author Tristan Tarrant
 */
public class ProcessorInfo {

   private ProcessorInfo() {
   }

   /**
    * Returns the number of processors available to this process. On most operating systems this method
    * simply delegates to {@link Runtime#availableProcessors()}. However, before Java 10, under Linux this strategy
    * is insufficient, since the JVM does not take into consideration the process' CPU set affinity and the CGroups
    * quota/period assignment. Therefore this method will analyze the Linux proc filesystem
    * to make the determination. Since the CPU affinity of a process can be change at any time, this method does
    * not cache the result. Calls should be limited accordingly.
    * <br>
    * The number of available processors can be overridden via the system property <code>infinispan.activeprocessorcount</code>,
    * e.g. <code>java -Dinfinispan.activeprocessorcount=4 ...</code>. Note that this value cannot exceed the actual number
    * of available processors.
    * <br>
    * Since Java 10, this can also be achieved via the VM flag <code>-XX:ActiveProcessorCount=xx</code>.
    * <br>
    * Note that on Linux, both SMT units (Hyper-Threading) and CPU cores are counted as a processor.
    *
    * @return the available processors on this system.
    */
   public static int availableProcessors() {
      return org.infinispan.commons.jdkspecific.ProcessorInfo.availableProcessors();
   }

   public static void main(String args[]) {
      System.out.println(availableProcessors());
   }
}
