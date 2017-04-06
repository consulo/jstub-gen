package consulo.internal.jstubgen;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.jetbrains.org.objectweb.asm.AnnotationVisitor;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.Type;

public class Main
{
	public static void main(String[] args) throws Exception
	{
		if(args.length != 1)
		{
			System.out.println("Required: <jar path>");
			return;
		}

		Path tempDirectory = Files.createTempDirectory("jstubgen");

		unzip(args[0], tempDirectory.toFile());

		List<Path> classFiles = new ArrayList<>();
		Files.walk(tempDirectory).forEach(path ->
		{
			if(path.toString().endsWith(".class"))
			{
				classFiles.add(path);

				byte[] bytes;
				try (InputStream stream = Files.newInputStream(path))
				{
					ClassReader reader = new ClassReader(stream)
					{
						@Override
						public void accept(ClassVisitor classVisitor, int flags)
						{
							super.accept(new ClassVisitor(Opcodes.API_VERSION, classVisitor)
							{
								@Override
								public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
								{
									MethodVisitor methodVisitor = super.visitMethod(access, name, desc, signature, exceptions);
									if(Modifier.isAbstract(access))
									{
										return methodVisitor;
									}

									if(methodVisitor != null)
									{
										return new MethodVisitor(Opcodes.API_VERSION)
										{
											@Override
											public AnnotationVisitor visitAnnotation(String desc, boolean visible)
											{
												return methodVisitor.visitAnnotation(desc, visible);
											}

											@Override
											public void visitParameter(String name, int access)
											{
												methodVisitor.visitParameter(name, access);
											}

											@Override
											public void visitCode()
											{
												String internalName = Type.getInternalName(UnsupportedOperationException.class);
												methodVisitor.visitTypeInsn(Opcodes.NEW, internalName);
												methodVisitor.visitInsn(Opcodes.DUP);
												methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false);
												methodVisitor.visitInsn(Opcodes.ATHROW);
											}
										};
									}
									return null;
								}
							}, flags);
						}
					};

					ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
					reader.accept(writer, ClassReader.SKIP_DEBUG);

					bytes = writer.toByteArray();
				}
				catch(IOException e)
				{
					throw new RuntimeException(e);
				}

				try
				{
					Files.write(path, bytes);
				}
				catch(IOException e)
				{
					throw new RuntimeException(e);
				}
			}
		});

		File jarGenFile = new File(args[0].replace(".jar", "-stub.jar"));

		if(jarGenFile.exists())
		{
			jarGenFile.delete();
		}

		try (ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(jarGenFile), StandardCharsets.UTF_8))
		{
			for(Path classFile : classFiles)
			{
				Path temp = tempDirectory.relativize(classFile);

				String entryName = temp.toString().replace("\\", "/");

				try (InputStream fileStream = Files.newInputStream(classFile))
				{
					addToZipFile(entryName, fileStream, zipOutputStream);
				}
			}
		}
	}

	public static void addToZipFile(String entryName, InputStream stream, ZipOutputStream zos) throws IOException
	{
		ZipEntry zipEntry = new ZipEntry(entryName);
		zos.putNextEntry(zipEntry);

		byte[] bytes = new byte[1024];
		int length;
		while((length = stream.read(bytes)) >= 0)
		{
			zos.write(bytes, 0, length);
		}

		zos.closeEntry();
	}

	public static void unzip(String zipFilePath, File destDir) throws IOException
	{
		if(!destDir.exists())
		{
			destDir.mkdir();
		}

		try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath)))
		{
			ZipEntry entry = zipIn.getNextEntry();
			while(entry != null)
			{
				String filePath = destDir.getPath() + File.separator + entry.getName();
				if(!entry.isDirectory())
				{
					extractFile(zipIn, filePath);
				}
				else
				{
					File dir = new File(filePath);
					dir.mkdir();
				}
				zipIn.closeEntry();
				entry = zipIn.getNextEntry();
			}
		}
	}

	private static void extractFile(ZipInputStream zipIn, String filePath) throws IOException
	{
		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath)))
		{
			byte[] bytesIn = new byte[1024];
			int read;
			while((read = zipIn.read(bytesIn)) != -1)
			{
				bos.write(bytesIn, 0, read);
			}
		}
	}
}
