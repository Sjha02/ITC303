package violet.gatherers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;

import org.primefaces.json.JSONArray;
import org.primefaces.json.JSONException;
import org.primefaces.json.JSONObject;

import violet.jpa.FactoryManager;
import violet.jpa.Game;
import violet.jpa.Genre;
import violet.jpa.Image;


/**
 * 
 *   This uses an UNOFFICIAL API for parsing titles for the 
 *   XBOX platforms, xboxapi.com.
 *   
 *   An API key is needed for every request. It's an optional paid service,
 *   We signed using the FREE package, which allows 120 API Requests per hour.
 *   
 *   
 * Based heavily on SteamGatherer, since it's mostly the same JSON objects query.
 * 
 * 
 * @author Erin and implemented Gatherer by somer
 */
public class XBoxGatherer extends Gatherer {
	private static final SimpleDateFormat[] dateFormatters = { // used to process release dates, attempts each one if the one before it fails
			new SimpleDateFormat("d MMM, yyyy"),
			new SimpleDateFormat("MMM d, yyyy"),
			//Added a pattern for dates with style similar to 2018-12-31T00:00:00Z
			new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'"),
			new SimpleDateFormat("yyy-MM-dd'T'HH:mm:ss")
	};
	
	EntityManager em;
	EntityTransaction transaction;
	//API_KEY for the unofficial XBOX API.
	private static final String API_KEY="3728619a15a6e772ea3b7a33ba1746cf5766ca29";
	private static final String COLUMN_NAME = "xbox_store_id";
	
	private AtomicInteger gamesGrabbed; // keeps track of the number of games grabbed
	
	private ExecutorService executor = null;
	
	private BlockingQueue<JSONObject> fullData; // full data already queried, the list contains all the needed data.
	private BlockingQueue<String> savedIds; // ids from titles already saved
	
	public XBoxGatherer() {
		gamesGrabbed = new AtomicInteger();
		fullData = new LinkedBlockingDeque<JSONObject>();
		savedIds = new LinkedBlockingDeque<String>();
	}
	
	private static final Pattern[] patterns = {
		Pattern.compile("<a.*?>.*?</a>"),
		Pattern.compile("<img.*?>")
	};
	
	public void gather(int maxGames) {
		super.gather(maxGames);
		
		try {
			queryApps();
		} catch(JSONException e) {
			Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Exception thrown during gathering", e);
			return;
		} catch(IOException e) {
			Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Exception thrown during gathering", e);
			return;
		}
		
		this.maxGames = maxGames;
		
		executor = Executors.newFixedThreadPool(THREAD_COUNT);
		for(int i=0; i<THREAD_COUNT; i++) // run our requests in multiple threads to reduce time waiting on connections
			executor.execute(new XBOXGathererRunnable());
		executor.shutdown();
		
		try {
			if(!executor.awaitTermination(4, TimeUnit.DAYS)) { // Let it run for 4 days before assuming it's halted or just gone too long
				Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Gatherer failed after 4 days execution");
			}
		} catch(InterruptedException e) {
			Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Execution was interrupted");
		}
	}
	
	public void interrupt() {
		super.interrupt();
		if(executor != null)
			executor.shutdownNow();
	}
	
	/**
	 * Grabs the list of games from the XBox Unofficial API.
	 * 
	 * The way this works, we should define the type  of content, for now
	 * it's set up to "games" for XBox One titles in the getGamesFrom() method 
	 * (explained in detail below).
	 * 
	 * And we set the page number 1, which will return the latest (or yet to be released)
	 * games.
	 * 
	 * 
	 * @throws JSONException
	 * @throws IOException
	 */
	private void queryApps() throws JSONException, IOException {
		List<String> existing_ids = getExistingGameIds(COLUMN_NAME, String.class);
		Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Query apps " + (insertOnly ? "insert only" : "update"));
		
		JSONArray apps = getGamesFrom("games",1).getJSONArray("Items");
		for(int i=apps.length()-1; i>=0; i--) {
			JSONObject app = apps.getJSONObject(i);
			
			if(!insertOnly || !existing_ids.contains(app.getString("id")) && !fullData.contains(app)) { // if we're inserting only, ignore apps that already exist
				try {
					fullData.put(app);
				} catch(InterruptedException e) {
					return;
				}
			}
		}
	}
	
	/**
    * getGamesFrom() builds the URL that will be used to query from the API, and
    *that returns the list of games. The API divides the endpoints for XBOX360 games,
    *XBOXOne games, and XBOXOne apps. These are the endpoints, respectively:
    * /v2/browse-marketplace/xbox360/, 	/v2/browse-marketplace/games/ and
    * /v2/browse-marketplace/apps/
    *
    * The API Key must be added as a header property called X-Auth. So, we 
    * must build the HttpURLConnection here insted of using the existing 
    * jsonFromURL in Gatherer.java.
    * 
    * That method could be improved so we could pass request properties for 
    * cases like this one.
    *
    * Each page returns always 20 items. We can set the start page, starting from 1.
    * 
    *
    * @param content: type of games to be retrieved "xbox360" for XBOX360 games,
    * "games" for XBOXOne games or "apps" for non-game titles
    * @param page: start page
	* @return JSONObject with retrieved titles
    */
    
    private JSONObject getGamesFrom(String content, int page) throws IOException{
        /*
        The games will be returned ordered by releaseDate, showing first those
        yet to be released.
        */
        String url = "https://xboxapi.com/v2/browse-marketplace/"+content+"/"+page+"?sort=releaseDate";

		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		con.setRequestMethod("GET");
		con.setRequestProperty("X-AUTH", API_KEY);

		int responseCode = con.getResponseCode();
		System.out.println("Response Code : " + responseCode);
                
                /*If the responseCode is 401, it means the provided API key
                doesn't have the rights, or that it wasn't added as header*/
                if(responseCode==401){
                      System.err.println("API Key wasn't provided or it's invalid.");
                      return null;
                }
                BufferedReader in= new BufferedReader(
		        new InputStreamReader(con.getInputStream(), Charset.forName("UTF-8")));
                
		String inputLine;
		StringBuffer response = new StringBuffer();

		while ((inputLine = in.readLine()) != null) {
			response.append(inputLine);
                        
		}
		in.close();
        return new JSONObject(response.toString());
        
    }
	
	
	/**
	 * Queries XBOXAPI.com for titles
	 * @author Erin and implemented Gatherer by somer
	 */
	private class XBOXGathererRunnable implements Runnable {
		private EntityManager em;
		
		public void run() {
			em = FactoryManager.pullCommonEM();
			FactoryManager.pullTransaction();
			try {
				JSONObject rowData;
				while((rowData = fullData.poll(QUEUE_TIMEOUT, TimeUnit.SECONDS)) != null) { // grab an id from the list to check
					Integer i = null;
					if(processAppData(rowData, em)) { // if we successfully grab a game, increment gamesGrabbed
						i = gamesGrabbed.incrementAndGet();
//						Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Saved " + id);
					}
					
					if(i != null && i > 0 && i % BATCH_SIZE == 0) { // flush the transaction if it's overdue to keep the transaction from becoming too large
						FactoryManager.flushCommonEM();
						Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Flushed cache");
					}
					
					if(maxGames > 0 && gamesGrabbed.get() >= maxGames) // if we've grabbed as many games as we need to, break the loop
						break;
				}
			} catch(InterruptedException e) {
				FactoryManager.reopenTransaction();
				Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Gathering interrupted");
			} catch(Exception e) { // if a more substantial exception was raised
				FactoryManager.rollbackTransaction();
				Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Exception thrown during gathering", e);
				
				executor.shutdownNow(); // cancel all other runners too 
			} finally {
				FactoryManager.popTransaction();
				FactoryManager.popCommonEM();
			}
		}
		
		
		
		
		
		/**
		 * Processes JSONData of a XBox title entry.
		 * @param appId
		 * @param data
		 * @param em
		 * @return true if the app is persisted
		 * @throws JSONException
		 * @throws IOException
		 * @throws InterruptedException
		 */
		private boolean processAppData(JSONObject data, EntityManager em) throws JSONException, IOException, InterruptedException {
			
			
			
			String appId = data.getString("ID");
			if(savedIds.contains(appId))
				return false;
			else // ensure no other runners attempt to process this same app. It's worth noting that this shouldn't be necessary and I'm unsure why I've got it - somer
				savedIds.offer(appId, QUEUE_TIMEOUT, TimeUnit.SECONDS);
			
			boolean exists = false; // check if the game exists in our database or we're inserting it
			Game game = getGame(em, COLUMN_NAME, appId);
			if(game != null)
				exists = true;
			else
				game = new Game();
			
			if(game.getXBoxStoreId() != appId) // set the XBox Store ID
				game.setXBoxStoreId(appId);
			
			String value = data.getString("Name");
			if(game.getName() != value) // set the name
				game.setName(value);
			
			Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Processing " + appId + ":" + value);
			
		
			
			// save the hero image
			value = data.getJSONArray("Images").getJSONObject(0).getString("Url");
			if(!value.isEmpty()) {
				Image heroImage = Image.saveImage(new URL(value));
				if(heroImage != null)
					game.setHeroImage(heroImage);
			}
			
			String releaseDate;
			if(data.has("ReleaseDate") && (releaseDate = data.getString("ReleaseDate")) != null) {
					/*
					 * Since the ReleaseDate in the XBOX API results
					 * has a format of "yyyy-mm-dd hh:mm:ss", with a space
					 * between, we must replace the space between date and
					 * time with a T, so that we can use a valid dateformat for it.
					 * */
					value = releaseDate.replace(" ", "T");
					boolean success = false;
					for(int i=0; i<dateFormatters.length; i++) { // try to process the release date with each formatter we have entered
						if(!releaseDate.trim().equals("")) {
							Calendar currentDate = Calendar.getInstance();
							int currentYear = currentDate.get(Calendar.YEAR);
							
							try {
								Date release = dateFormatters[i].parse(value);
								
								Calendar cal = Calendar.getInstance();
								cal.setTime(release);
								if(cal.get(Calendar.YEAR) - currentYear < 100) // Check we're not being told the game releases in 2799
									game.setRelease(release);
								success = true; // Still mark it a success even if the release date was insane
								
								break; // the first time we succeed, break
							}
							catch(ParseException e) {}
							catch(NumberFormatException ex){}
						}
					}
					
					if(!success)
						Logger.getLogger(this.getClass().getName()).log(Level.WARNING, "Failed to parse release date " + value);
				
			}
			
			if(!exists)
				game = em.merge(game); // if the game already exists, merge it to ensure it is up to date
			
			synchronized(this.getClass()) { // make sure only one runner enters this block at a time to stop duplicate genres in the database
				boolean commit = false;
				JSONArray genres;
				if(data.has("Genres") &&  (genres = data.getJSONArray("Genres")) != null) {
					commit = commit || genres.length() > 0;
					
					for(int i=0; i<genres.length(); i++) {
						
						Genre genre;
						JSONObject object = genres.getJSONObject(i);
						
						String name=object.getString("Name");
						genre = Genre.getGenre(name, true, em);
						//Logger.getLogger(this.getClass().getName()).log(Level.INFO, game.getName() + " Genre " + genre.getName());
						
						game.addGenre(genre);
					}
				}
				
			
				
				/*
				 * oddly we can't use em.flush, it seems to cause a deadlock whereas committing the transaction
				 * and starting a new one will work to ensure the genres of another thread are up to date
				 * and we don't have duplicate INSERT statements
				 */
				if(commit) {
					FactoryManager.reopenTransaction();
				}
			}
			
			
			
			return true;
		}
	
	
	}
}
