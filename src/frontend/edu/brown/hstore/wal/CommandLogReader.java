package edu.brown.hstore.wal;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.BufferUnderflowException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.voltdb.messaging.FastDeserializer;
import org.voltdb.utils.CompressionService;
import org.voltdb.utils.DBBPool;
import org.voltdb.utils.NotImplementedException;
import org.voltdb.utils.DBBPool.BBContainer;

public class CommandLogReader implements Iterable<LogEntry> {
    
    final FastDeserializer fd;
    final Map<Integer, String> procedures;
    boolean groupCommit;
    
    public CommandLogReader(String path) {
        FileChannel roChannel = null;
        ByteBuffer readonlybuffer = null;
        
        File f = new File(path);
        try {
            roChannel = new RandomAccessFile(f, "r").getChannel();
            readonlybuffer = roChannel.map(FileChannel.MapMode.READ_ONLY, 0, (int)roChannel.size());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        assert(readonlybuffer != null);
        this.fd = new FastDeserializer(readonlybuffer);
        
        this.procedures = this.readHeader();
    }
    
    @Override
    public Iterator<LogEntry> iterator() {
        Iterator<LogEntry> it = new Iterator<LogEntry>() {
            FastDeserializer decompressedFd;
            private LogEntry _next;
            {
                decompressedFd = new FastDeserializer(ByteBuffer.allocate(0));
                
                this.next();
            }
            @Override
            public boolean hasNext() {
                return _next != null;
                //return fd.buffer().hasRemaining();
            }

            @Override
            public LogEntry next() {
                LogEntry ret = _next;
                _next = null;
                
                //Fill the decompressed buffer if it is empty
                if (groupCommit && !decompressedFd.buffer().hasRemaining()) {
                    try {
                        int sizeCompressed = fd.readInt();
                        byte[] b = new byte[sizeCompressed];
                        fd.readFully(b);
                        byte[] decompressed = CompressionService.decompressBytes(b);
                        this.decompressedFd.setBuffer(ByteBuffer.wrap(decompressed));
                    } catch (IOException ex) {
                        throw new RuntimeException("Failed to decompress data from the WAL file!", ex);
                    } catch (BufferUnderflowException ex) {
                        this.decompressedFd.setBuffer(ByteBuffer.allocate(0));
                    }
                }
                
                try {
                    if (groupCommit) {
                        _next.finish();
                        _next.readExternal(decompressedFd);
                    }
                    else {
                        _next = fd.readObject(LogEntry.class);
                    }
                } catch (IOException ex) {
                    throw new RuntimeException("Failed to deserialize LogEntry!", ex);
                } catch (BufferUnderflowException ex) {
                    _next = null;
                }
                return (ret);
            }

            @Override
            public void remove() {
                throw new NotImplementedException("Can't call remove! You crazy!");
            }
        };
        return (it);
        
        // TODO: We need to figure out how we want to read these entries
        // back in. I suppose we could just make a new LocalTransaction
        // entry each time. What we really should do is recreate
        // the StoredProcedureInvocation and then pass that into
        // the HStoreSite so that we can replay the transaction
        // 
        // So maybe we want to make this a StoredProcedure Invocation iterator?
    }
    
    /**
     * 
     * @return
     */
    protected Map<Integer, String> readHeader() {
        Map<Integer, String> procedures = new HashMap<Integer, String>();
        
        try {
            this.groupCommit = fd.readBoolean();
            int num_procs = fd.readInt();
            for (int i = 0; i < num_procs; i++)
                procedures.put(new Integer(fd.readInt()), fd.readString());
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read WAL log header!", ex);
        }
        
        return (procedures);
    }
}
