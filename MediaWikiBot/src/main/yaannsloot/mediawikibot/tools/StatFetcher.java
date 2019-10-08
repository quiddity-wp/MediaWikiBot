package main.yaannsloot.mediawikibot.tools;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import main.yaannsloot.mediawikibot.exceptions.WikiProjectNotFoundException;

/**
 * Used to retrieve information from https://wikistats.wmflabs.org such as the
 * current list of mediawiki projects and all api endpoints of existing wikis in
 * each project.
 * 
 * @author Ian Sloat
 *
 */
public class StatFetcher {

	private static final Logger logger = LoggerFactory.getLogger(StatFetcher.class);
	

	/**
	 * Creates a new StatFetcher object
	 */
	public StatFetcher() {
		logger.info("Created new stat fetcher");
	}

	/**
	 * Gets the complete list of mediawiki projects found on https://wikistats.wmflabs.org
	 * 
	 * @return A list containing the string names of each of the mediawiki projects
	 * @throws IOException If the program failed to connect to
	 *                     https://wikistats.wmflabs.org
	 */
	public List<String> getMediaWikis() throws IOException {
		List<String> result = new ArrayList<String>();
		logger.info("Attempting to retrieve the mediawiki project list from https://wikistats.wmflabs.org...");
		Document doc = Jsoup.connect("https://wikistats.wmflabs.org/").get();
		Elements elements = doc.getElementsByAttribute("href");
		Elements sortHeap = new Elements();
		for (Element e : elements) {
			if (e.attr("href").startsWith("display.php?t=")) {
				sortHeap.add(e);
			}
		}
		elements = sortHeap;
		elements.forEach(e -> result.add(e.text()));
		logger.info("Project list retrieved successfully");
		return result;
	}

	/**
	 * Retrieves all api endpoints related to a mediawiki project
	 * @param project The project to search in
	 * @param language The desired language to retrieve endpoints for
	 * @return A list containing all endpoints related to the project specified
	 * @throws WikiProjectNotFoundException If the mediawiki project is not listed on https://wikistats.wmflabs.org
	 * @throws IOException If the program does not have permission to create temporary files
	 */
	public List<String> getProjectEndpoints(String project, String language)
			throws WikiProjectNotFoundException, IOException {
		List<String> result = new ArrayList<String>();
		List<String> wikiLinks = new ArrayList<String>();
		logger.info("Attempting to retrieve list of wikis from project \"" + project + "\"...");
		URL site = new URL("https://wikistats.wmflabs.org/api.php?action=dump&table=" + project + "&format=csv");
		File temp = File.createTempFile("records", "csv");
		FileUtils.copyURLToFile(site, temp);
		String doc = FileUtils.readFileToString(temp, Charset.defaultCharset());
		temp.delete();
		if (doc.contains("table name not set or unknown")) {
			throw new WikiProjectNotFoundException(project);
		} else {
			CSVParser projectData = CSVParser.parse(doc,
					CSVFormat.RFC4180.withHeader().withDelimiter(',').withRecordSeparator('\n'));
			List<CSVRecord> records = projectData.getRecords();
			logger.info(records.size() + " wikis found");
			if (projectData.getHeaderMap().containsKey("lang")) {
				records = records.stream().filter(rcd -> rcd.get("lang").equals(language) || rcd.get("lang").equals(""))
						.collect(Collectors.toList());
				logger.info(records.size() + " wikis either in " + language + " or unspecified");
			} else {
				logger.info("No languages specified in retrieved records so restrictions will be ignored");
			}
			logger.info("Attempting to retrieve " + records.size() + " wiki urls...");
			if (projectData.getHeaderMap().containsKey("prefix")) {
				Document projects = Jsoup.connect("https://wikistats.wmflabs.org/").ignoreContentType(true).get();
				Elements elements = projects.getElementsByAttribute("href");
				String displayUrl = "";
				for (Element e : elements) {
					if (e.attr("href").startsWith("display.php?t=") && e.text().equals(project)) {
						displayUrl = "https://wikistats.wmflabs.org/" + e.attr("href");
						break;
					}
				}
				Document display = Jsoup.connect(displayUrl).get();
				Elements displayLinks = display.getElementsByAttribute("href");
				List<String> prefixes = new ArrayList<String>();
				for (CSVRecord rcd : records) {
					prefixes.add(rcd.get("prefix"));
				}
				String urlTemplate = "";
				for (Element e : displayLinks) {
					if (prefixes.contains(e.text())) {
						try {
							urlTemplate = "http://" + new URL(e.attr("href")).getHost().replace(e.text(), ";;;");
							break;
						} catch (MalformedURLException e1) {
						}
					}
				}
				for (String prefix : prefixes) {
					wikiLinks.add(urlTemplate.replace(";;;", prefix));
				}
			} else if (projectData.getHeaderMap().containsKey("statsurl")) {
				for (CSVRecord rcd : records) {
					try {
						int http = Integer.parseInt(rcd.get("http"));
						if (http == 200 || http == 404)
							wikiLinks.add("http://" + new URL(rcd.get("statsurl")).getHost());
					} catch (MalformedURLException e) {
					}
				}
			}

			logger.info(wikiLinks.size() + " wiki links found. Verifying endpoints...");
			class verifyThread implements Runnable {
				private int urlNumber;

				public verifyThread(int urlNumber) {
					this.urlNumber = urlNumber;
				}

				public void run() {
					HttpURLConnection connection;
					try {
						connection = (HttpURLConnection) new URL(wikiLinks.get(urlNumber)).openConnection();
						connection.setRequestMethod("GET");
						connection.setConnectTimeout(60000);
						connection.connect();
						if (connection.getResponseCode() != 200) {
							wikiLinks.set(urlNumber, wikiLinks.get(urlNumber).replace("http://", "https://"));
							connection = (HttpURLConnection) new URL(wikiLinks.get(urlNumber)).openConnection();
							connection.setRequestMethod("GET");
							connection.setConnectTimeout(60000);
							connection.connect();
						}
						if (connection.getResponseCode() != 200) {
							logger.info("(" + wikiLinks.get(urlNumber) + "): Returned " + connection.getResponseCode()
									+ ". Skipping...");
						} else {
							logger.info("(" + wikiLinks.get(urlNumber) + ")(Attempt 1): Pinging "
									+ wikiLinks.get(urlNumber) + "/api.php...");
							connection = (HttpURLConnection) new URL(wikiLinks.get(urlNumber) + "/api.php")
									.openConnection();
							connection.setRequestMethod("GET");
							connection.setConnectTimeout(60000);
							connection.connect();
							if (connection.getResponseCode() == 200) {
								result.add(wikiLinks.get(urlNumber) + "/api.php");
								logger.info("(" + wikiLinks.get(urlNumber) + "): Endpoint " + wikiLinks.get(urlNumber)
										+ "/api.php verified.");
							} else {
								logger.info("(" + wikiLinks.get(urlNumber) + ")(Attempt 2): Pinging "
										+ wikiLinks.get(urlNumber) + "/w/api.php...");
								connection = (HttpURLConnection) new URL(wikiLinks.get(urlNumber) + "/w/api.php")
										.openConnection();
								connection.setRequestMethod("GET");
								connection.setConnectTimeout(60000);
								connection.connect();
								if (connection.getResponseCode() == 200) {
									result.add(wikiLinks.get(urlNumber) + "/w/api.php");
									logger.info("(" + wikiLinks.get(urlNumber) + "): Endpoint "
											+ wikiLinks.get(urlNumber) + "/w/api.php verified.");
								} else {
									logger.info("(" + wikiLinks.get(urlNumber) + ")(Attempt 3): Pinging "
											+ wikiLinks.get(urlNumber) + "/wiki/api.php...");
									connection = (HttpURLConnection) new URL(wikiLinks.get(urlNumber) + "/wiki/api.php")
											.openConnection();
									connection.setRequestMethod("GET");
									connection.setConnectTimeout(60000);
									connection.connect();
									if (connection.getResponseCode() == 200) {
										result.add(wikiLinks.get(urlNumber) + "/wiki/api.php");
										logger.info("(" + wikiLinks.get(urlNumber) + "): Endpoint "
												+ wikiLinks.get(urlNumber) + "/wiki/api.php verified.");
									} else {
										logger.info("(" + wikiLinks.get(urlNumber) + "): Endpoint could not be found");
									}
								}
							}
						}
					} catch (Exception e) {
						logger.warn(
								"(" + wikiLinks.get(urlNumber) + "): Could not establish a proper connection to host");
					}
				}
			}
			List<Thread> threadStack = new ArrayList<Thread>();
			for (int i = 0; i < wikiLinks.size(); i++) {
				threadStack.add(new Thread(new verifyThread(i)));
			}
			for (Thread t : threadStack) {
				t.start();
			}
			for (Thread t : threadStack) {
				try {
					t.join();
				} catch (InterruptedException e1) {
					e1.printStackTrace();
				}
			}
			logger.info(result.size() + " endpoints verified successfully.");
		}
		return result;
	}
	
	

}
