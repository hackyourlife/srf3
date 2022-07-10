import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class SteinbergResourceFile {
	private static final String HEADER = "Steinberg Resource File\r\n";
	private static final int SRF3_MAGIC = 0x53524633;
	private static final int SRFF_MAGIC = 0x53524646;

	private int format;

	private byte[] data;

	private FolderEntry directory = new FolderEntry(null, null, 0);

	public SteinbergResourceFile(byte[] data) throws IOException {
		this.data = data;

		parse();
	}

	private static int get32bitBE(byte[] data, int offset) {
		return Byte.toUnsignedInt(data[offset]) << 24 | Byte.toUnsignedInt(data[offset + 1]) << 16 |
				Byte.toUnsignedInt(data[offset + 2]) << 8 | Byte.toUnsignedInt(data[offset + 3]);
	}

	private static int get32bitLE(byte[] data, int offset) {
		return Byte.toUnsignedInt(data[offset]) | Byte.toUnsignedInt(data[offset + 1]) << 8 |
				Byte.toUnsignedInt(data[offset + 2]) << 16 | Byte.toUnsignedInt(data[offset + 3]) << 24;
	}

	private static long get64bitLE(byte[] data, int offset) {
		return Byte.toUnsignedLong(data[offset]) |
				Byte.toUnsignedLong(data[offset + 1]) << 8 |
				Byte.toUnsignedLong(data[offset + 2]) << 16 |
				Byte.toUnsignedLong(data[offset + 3]) << 24 |
				Byte.toUnsignedLong(data[offset + 4]) << 32 |
				Byte.toUnsignedLong(data[offset + 5]) << 40 |
				Byte.toUnsignedLong(data[offset + 6]) << 48 |
				Byte.toUnsignedLong(data[offset + 7]) << 56;
	}

	private void parse() throws IOException {
		for(int i = 0; i < HEADER.length(); i++) {
			if(data[i] != HEADER.charAt(i)) {
				throw new IOException("not an SRF file");
			}
		}

		int signature = get32bitBE(data, data.length - 4);
		if(signature != SRF3_MAGIC && signature != SRFF_MAGIC) {
			throw new IOException("not an SRF3/SRFF file");
		}

		format = signature;

		int footerSize = get32bitLE(data, data.length - 8);
		int entryCount = get32bitLE(data, data.length - 12);
		int length = get32bitLE(data, data.length - 16);

		int offset = data.length - length - footerSize;

		FolderEntry folder = new FolderEntry(null, null, 0);
		directory = folder;

		int ptr = offset;
		int cnt = 0;
		while(ptr < data.length - footerSize) {
			Entry entry = parseEntry(folder, ptr);
			ptr += entry.getMetadataSize();
			cnt++;

			if(entry instanceof FolderEntry) {
				if(entry.getName().equals("..")) {
					folder = folder.getParent();
				} else {
					folder.addEntry(entry);
					folder = (FolderEntry) entry;
				}
			} else {
				folder.addEntry(entry);
			}
		}

		if(cnt != entryCount) {
			throw new IOException("expected " + entryCount + " entries, got " + cnt + " entries");
		}
	}

	private Entry parseEntry(FolderEntry folder, int offset) throws IOException {
		int ptr = offset;
		int type = get32bitLE(data, ptr);
		ptr += 4;
		long[] values;
		switch(type) {
		case 0x09: // folder
			values = new long[2];
			break;
		case 0x0A: // uncompressed file
			values = new long[2];
			break;
		case 0x0E: // compressed file
			values = new long[3];
			break;
		default:
			throw new IOException("error at " + Integer.toHexString(ptr - 4) + " (0x" +
					Integer.toHexString(offset) + ")");
		}

		if(format == SRF3_MAGIC) {
			for(int i = 0; i < values.length; i++) {
				values[i] = get64bitLE(data, ptr);
				ptr += 8;
			}
		} else if(format == SRFF_MAGIC) {
			for(int i = 0; i < values.length; i++) {
				values[i] = get32bitLE(data, ptr);
				ptr += 4;
			}
		} else {
			throw new IOException("unknown file format " + format);
		}

		StringBuilder buf = new StringBuilder();
		while(data[ptr] != 0) {
			buf.append((char) (data[ptr] & 0xFF));
			ptr++;
		}
		ptr++; // skip NUL

		String name = buf.toString();

		int size = ptr - offset;

		switch(type) {
		case 0x09: // folder
			return new FolderEntry(folder, name, size);
		case 0x0A: // uncompressed file
			return new UncompressedFileEntry(name, size, values[0], values[1]);
		case 0x0E: // uncompressed file
			return new CompressedFileEntry(name, size, values[0], values[1], values[2]);
		default:
			throw new IOException("error at 0x" + Integer.toHexString(offset) + ": unknown entry type 0x" +
					Integer.toHexString(type));
		}
	}

	public List<Entry> getFiles() {
		return directory.getEntries();
	}

	private abstract static class Entry {
		private final String name;
		private final int metadataSize;

		public Entry(String name, int metadataSize) {
			this.name = name;
			this.metadataSize = metadataSize;
		}

		public String getName() {
			return name;
		}

		public int getMetadataSize() {
			return metadataSize;
		}
	}

	private abstract static class FileEntry extends Entry {
		public FileEntry(String name, int metadataSize) {
			super(name, metadataSize);
		}

		public abstract long getLength();

		public abstract byte[] getData() throws IOException;
	}

	public class UncompressedFileEntry extends FileEntry {
		private final long offset;
		private final long length;

		public UncompressedFileEntry(String name, int metadata, long offset, long length) {
			super(name, metadata);
			this.offset = offset;
			this.length = length;
		}

		@Override
		public long getLength() {
			return length;
		}

		@Override
		public byte[] getData() {
			byte[] result = new byte[(int) length];
			System.arraycopy(data, (int) offset, result, 0, (int) length);
			return result;
		}

		@Override
		public String toString() {
			return getName() + " @ 0x" + Long.toUnsignedString(offset, 16) + " (" + length + " bytes)";
		}
	}

	public class CompressedFileEntry extends FileEntry {
		private final long offset;
		private final long compressedLength;
		private final long uncompressedLength;

		public CompressedFileEntry(String name, int metadata, long offset, long compressedLength,
				long uncompressedLength) {
			super(name, metadata);
			this.offset = offset;
			this.compressedLength = compressedLength;
			this.uncompressedLength = uncompressedLength;
		}

		@Override
		public long getLength() {
			return uncompressedLength;
		}

		@Override
		public byte[] getData() throws IOException {
			Inflater inflater = new Inflater();
			inflater.setInput(data, (int) offset, (int) compressedLength);
			byte[] result = new byte[(int) uncompressedLength];
			try {
				int length = inflater.inflate(result);
				if(length != uncompressedLength) {
					throw new RuntimeException("failed to inflate");
				}
				return result;
			} catch(DataFormatException e) {
				throw new IOException(e);
			}
		}

		@Override
		public String toString() {
			return getName() + " @ 0x" + Long.toUnsignedString(offset, 16) + " (" + compressedLength +
					" bytes compressed / " + uncompressedLength + " bytes uncompressed)";
		}
	}

	public static class FolderEntry extends Entry {
		private final FolderEntry parent;
		private final List<Entry> entries = new ArrayList<>();

		public FolderEntry(FolderEntry parent, String name, int metadata) {
			super(name, metadata);
			this.parent = parent;
		}

		public FolderEntry getParent() {
			return parent;
		}

		private void addEntry(Entry entry) {
			entries.add(entry);
		}

		public List<Entry> getEntries() {
			return Collections.unmodifiableList(entries);
		}

		@Override
		public String toString() {
			return "[" + getName() + "]";
		}
	}

	private static void dump(Path path, Entry entry) throws IOException {
		if(entry instanceof FolderEntry) {
			FolderEntry folder = (FolderEntry) entry;
			String name = folder.getName();
			Path subpath = path.resolve(name);
			File f = subpath.toFile();
			if(!f.exists()) {
				if(!f.mkdir()) {
					throw new IOException("Cannot create directory " + f);
				}
			}
			System.out.println("Folder " + subpath);
			for(Entry e : folder.getEntries()) {
				dump(subpath, e);
			}
		} else {
			FileEntry file = (FileEntry) entry;
			String name = entry.getName();
			Path subpath = path.resolve(name);
			System.out.println("File " + subpath);
			Files.write(subpath, file.getData(), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);
		}
	}

	public static void main(String[] args) throws IOException {
		if(args.length != 2) {
			System.out.println("Usage: SteinbergResourceFile input.srf output-folder");
			System.exit(1);
		}

		byte[] data = Files.readAllBytes(Paths.get(args[0]));
		SteinbergResourceFile srf = new SteinbergResourceFile(data);

		Path path = Paths.get(args[1]);
		for(Entry entry : srf.getFiles()) {
			dump(path, entry);
		}
	}
}
