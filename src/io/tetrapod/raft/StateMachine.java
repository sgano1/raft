package io.tetrapod.raft;

import java.io.*;
import java.util.zip.*;

import org.slf4j.*;

/**
 * The state machine applies commands to update state.
 * 
 * It contains the state we want to coordinate across a distributed cluster.
 */
public abstract class StateMachine<T extends StateMachine<T>> {

   public static final Logger logger                = LoggerFactory.getLogger(StateMachine.class);

   public static final int    SNAPSHOT_FILE_VERSION = 1;

   public static final int    COMMAND_ID_NEW_TERM   = -1;

   public enum SnapshotMode {
      /**
       * Blocking mode is memory efficient, but blocks all changes while writing the snapshot. Only suitable for small state machines that
       * can write out very quickly
       */
      Blocking,

      /**
       * Dedicated mode maintains an entire secondary copy of the state machine in memory for snapshots. This allows easy non-blocking
       * snapshots, at the expense of using more memory to hold the second state machine, and the processing time to apply commands twice.
       */
      Dedicated,

      /**
       * If your state machine can support copy-on-writes, this is the most efficient mode for non-blocking snapshots
       */
      CopyOnWrite
   }

   private long index;
   private long term;

   private long prevIndex;
   private long prevTerm;

   public StateMachine() {}

   public SnapshotMode getSnapshotMode() {
      return SnapshotMode.Blocking;
   }

   protected Command<T> makeCommandById(int id) {
      if (id < 0) {
         switch (id) {
            case COMMAND_ID_NEW_TERM:
               return new NewTermCommand<T>();
         }
      }
      return makeCommand(id);
   }

   public abstract Command<T> makeCommand(int id);

   public abstract void saveState(DataOutputStream out) throws IOException;

   public abstract void loadState(DataInputStream in) throws IOException;

   public void writeSnapshot(File file, long prevTerm) throws IOException {
      try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
         out.writeInt(SNAPSHOT_FILE_VERSION);
         out.writeLong(term);
         out.writeLong(index);
         out.writeLong(prevTerm);
         saveState(out);
      }
   }

   public void readSnapshot(File file) throws IOException {
      try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
         int version = in.readInt();
         assert (version <= SNAPSHOT_FILE_VERSION);
         term = in.readLong();
         index = in.readLong();
         prevIndex = index - 1;
         prevTerm = in.readLong();
         loadState(in);
      }
   }

   public static long getSnapshotIndex(File file) {
      try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
         int version = in.readInt();
         assert (version <= SNAPSHOT_FILE_VERSION);
         @SuppressWarnings("unused")
         long term = in.readLong();
         long index = in.readLong();
         return index;
      } catch (IOException e) {
         logger.error(e.getMessage(), e);
         return 0;
      }
   }

   public long getIndex() {
      return index;
   }

   public long getTerm() {
      return term;
   }

   public long getPrevIndex() {
      return prevIndex;
   }

   public long getPrevTerm() {
      return prevTerm;
   }

   @SuppressWarnings("unchecked")
   protected void apply(Entry<T> entry) {
      assert (this.index + 1 == entry.index) : (this.index + 1) + "!=" + entry.index;
      assert (this.term <= entry.term);
      entry.command.applyTo((T) this);
      this.index = entry.index;
      this.term = entry.term;
   }

   public static interface Factory<T> {
      public T makeStateMachine();
   }
}