package org.flashtool.parsers.simpleusblogger;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.flashtool.libusb.LibUsbException;
import org.flashtool.parsers.sin.SinFile;
import org.flashtool.util.BytesUtil;
import org.flashtool.util.HexDump;
import org.flashtool.util.StreamSearcher;
import org.riversun.bigdoc.bin.BigFileSearcher;

import com.igormaznitsa.jbbp.JBBPParser;
import com.igormaznitsa.jbbp.io.JBBPBitInputStream;


import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Parser {
	

	static JBBPParser USBRecord = JBBPParser.prepare(
			"<long irp;" +
			"<int unknown1;" + 
			"<int recordid;" + 
			"<int recordlength;" + 
			"byte[28] reserved2;"
			);

	static JBBPParser SinHeader = JBBPParser.prepare(
			"byte version;" +
			"byte[3] magic;" +
			"int headerLen;" +
			"int payloadType;" +
			"int hashType;" +
			"int reserved;" +
			"int hashLen;"
			);

	static JBBPParser S1Packet = JBBPParser.prepare(
			  "byte[4] command;"
			+ "byte[4] flag;"
			+ "byte[4] length;"
			+ "byte headercksum;"
			);

	//public static byte[] record = new byte[] {(byte)0x80,0x00,0x09,0x00,0x00,0x00,0x00,0x00};	
		  
	  public static Session parse(String usblog, String extractedsin) throws Exception {
		  
		  	Session session = new Session();
		  	S1Packet current=null;
		  	FlashCommand ccurrent=null;
		  	byte[] downloadContent = null;
			USBHeader head=null;
			FileInputStream fin=new FileInputStream(new File(usblog));
			boolean s1parser=true;
			
			StreamSearcher searcher = new StreamSearcher(BytesUtil.getBytes("1B0000001300000000000000"));
			long startpos=searcher.search(new FileInputStream(new File(usblog)));
			JBBPBitInputStream usbStream = new JBBPBitInputStream(fin);
			usbStream.skip(startpos-48);
			int recnum = 0;
			HashSet<String> set = new HashSet<String>();
			while (usbStream.hasAvailableData()) {
				USBRecord rec = readRecord(usbStream);
				rec.recnum=recnum++;				
				if (rec.header==null) continue;
				if (rec.header.usb_UsbDeviceHandle==0) continue;
				if (rec.getDataString().contains("getvar") && s1parser==true) s1parser=false;
				if (s1parser==false) {
					if (rec.getDirection().equals("WRITE")) {
						//if (rec.getDataString().matches("^[A-Za-z].*$") && rec.getData().length<100) 
							//System.out.println(rec.getDirection()+" : "+rec.getDataString());
						if (!rec.getDataString().equals("signature") &&
								!rec.getDataString().startsWith("Write-TA:") &&
								!rec.getDataString().startsWith("Read-TA:") &&
								!rec.getDataString().startsWith("getvar:") &&
								!rec.getDataString().equals("powerdown") &&
								!rec.getDataString().equals("Sync") &&
								!rec.getDataString().equals("Get-emmc-info") &&
								!rec.getDataString().startsWith("Getlog") &&
								!rec.getDataString().startsWith("set_active:") &&
								!rec.getDataString().startsWith("Get-ufs-info") &&
								!rec.getDataString().startsWith("Get-gpt-info:") &&
								!rec.getDataString().startsWith("flash:") &&
								!rec.getDataString().startsWith("erase:") &&
								!rec.getDataString().startsWith("download:") &&
								!rec.getDataString().startsWith("Repartition:") &&
								!rec.getDataString().equals("Get-root-key-hash")) {
							downloadContent = rec.getData();
						}
						if (rec.getDataString().equals("signature") ||
								rec.getDataString().startsWith("Write-TA:") ||
								rec.getDataString().startsWith("Read-TA:") ||
								rec.getDataString().startsWith("getvar:") ||
								rec.getDataString().equals("powerdown") ||
								rec.getDataString().equals("Sync") ||
								rec.getDataString().equals("Get-emmc-info") ||
								rec.getDataString().startsWith("Getlog") ||
								rec.getDataString().startsWith("set_active:") ||
								rec.getDataString().startsWith("Get-ufs-info") ||
								rec.getDataString().startsWith("Get-gpt-info:") ||
								rec.getDataString().equals("Get-root-key-hash")) {
							if (ccurrent!=null) {
								session.addCommand(ccurrent);
							}							
							ccurrent = new FlashCommand(rec.getDataString());
							if (rec.getDataString().startsWith("signature")) {
								ccurrent.addSignData(downloadContent);
								ccurrent.setFile(getSin(extractedsin, ccurrent.signdata));
							}
						}
						else {
							if (ccurrent.getCommand().startsWith("signature")) {
								if (ccurrent.getLastSubCommand().length()==0) {
									if (rec.getDataString().startsWith("erase") || rec.getDataString().startsWith("flash") || rec.getDataString().startsWith("Repartition") || rec.getDataString().startsWith("download")) {
										ccurrent.setSubCommand(rec.getDataString());
									}
									else
										ccurrent.addSignData(rec.getData());
								}
								else {
									if (rec.getDataString().startsWith("erase") || rec.getDataString().startsWith("flash") || rec.getDataString().startsWith("Repartition")) {
										ccurrent.setSubCommand(rec.getDataString());
									}
								}
							}
						}
					}
					else {
						if (ccurrent.getCommand().startsWith("Read-TA")) {
							if (!rec.getDataString().startsWith("DATA") && !rec.getDataString().startsWith("OKAY") && !rec.getDataString().startsWith("FAIL"))
							ccurrent.addReply(rec.getData());
						}
						if (ccurrent.getCommand().startsWith("getvar")) {
							ccurrent.addReply(rec.getData());
						}
					}
				}
				else {
					if (rec.data.length<13) {
						if (current!=null)
							current.addData(rec.data);
					}
					else {
						JBBPBitInputStream dataStream = new JBBPBitInputStream(new ByteArrayInputStream(rec.data));
						S1Packet p = S1Packet.parse(dataStream).mapTo(new S1Packet());
						if (p.isHeaderOK()) {
							if (rec.header.usb_TransferBufferLength > 13) {
								p.addData(dataStream.readByteArray(rec.data.length-13));
							}
							p.setRecord(rec.recnum);
							p.setDirection(rec.header.usb_TransferFlags);
							if (current!=null) {
								current.finalise();
									if (current.direction.equals("READ REPLY")) {
										if (current.getLength()>0)
											if (current.getCommand()!=6) {
												session.addPacket(current);											
											}
									} else {
										if (current.getCommand()==5)
											current.setFileName(getSin(extractedsin,current.data));
										if (current.getCommand()!=6)
											session.addPacket(current);
									}
							}
							current = p;
						}
						else {
							if (current!=null)
								current.addData(rec.data);
						}
						dataStream.close();
					}
				}
			}
			if (ccurrent!=null) {
				if (ccurrent.getCommand().startsWith("signature")) {
					ccurrent.setFile(getSin(extractedsin,ccurrent.signdata));
				}
				session.addCommand(ccurrent);
			}
			usbStream.close();
/*			Iterator<S1Packet> ipacket = session.getPackets();
			while (ipacket.hasNext()) {
				S1Packet p = ipacket.next();
			}*/
			return session;
	  }
	  
	  
	  private static USBRecord readRecord(JBBPBitInputStream usbStream) throws Exception {
		  USBRecord rec = USBRecord.parse(usbStream).mapTo(new USBRecord());
		  rec.parse(usbStream);
		  return rec;
		  
	  }
	  
	  private static String getSin(String folder, byte[] source) throws Exception {
		  Collection<File> sinfiles = FileUtils.listFiles(new File(folder), new String[] {"sin"}, true);
		  Iterator<File> ifiles = sinfiles.iterator();
		  while (ifiles.hasNext()) {
			  try {
				  SinFile  sinfile = new SinFile(ifiles.next());
				  if (sinfile.getVersion()!=4) {
					  JBBPBitInputStream sinStream = new JBBPBitInputStream(new FileInputStream(sinfile.getFile()));
					  byte[] res = sinStream.readByteArray(source.length);
					  if (Arrays.equals(source, res))
						  return sinfile.getShortName();
				  }
				  else {
					  if (Arrays.equals(source, sinfile.getHeader())) return sinfile.getFile().getName();
				  }
			  } catch (EOFException eof) {
			  }			  
		  }
		  return "Not identified";
	  }
}
