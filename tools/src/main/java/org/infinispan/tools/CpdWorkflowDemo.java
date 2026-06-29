package org.infinispan.tools;

import java.util.ArrayList;
import java.util.List;

/**
 * Throwaway class used to exercise the CPD GitHub Actions workflow
 * introduced in https://github.com/infinispan/infinispan/pull/17624.
 * Contains deliberate code duplication so the workflow has something to
 * detect; do NOT merge.
 */
public final class CpdWorkflowDemo {

   private CpdWorkflowDemo() {
   }

   public static List<Integer> processNumbersA(List<Integer> input) {
      List<Integer> result = new ArrayList<>();
      for (Integer value : input) {
         if (value == null) {
            continue;
         }
         int doubled = value * 2;
         int squared = doubled * doubled;
         int adjusted = squared + 17;
         int rescaled = adjusted / 3;
         int offset = rescaled + 42;
         if (offset > 2000) {
            result.add(offset - 2000);
         } else if (offset > 1000) {
            result.add(offset - 1000);
         } else if (offset < 0) {
            result.add(-offset);
         } else {
            result.add(offset);
         }
      }
      return result;
   }

   public static List<Integer> processNumbersB(List<Integer> input) {
      List<Integer> result = new ArrayList<>();
      for (Integer value : input) {
         if (value == null) {
            continue;
         }
         int doubled = value * 2;
         int squared = doubled * doubled;
         int adjusted = squared + 17;
         int rescaled = adjusted / 3;
         int offset = rescaled + 42;
         if (offset > 2000) {
            result.add(offset - 2000);
         } else if (offset > 1000) {
            result.add(offset - 1000);
         } else if (offset < 0) {
            result.add(-offset);
         } else {
            result.add(offset);
         }
      }
      return result;
   }
}

