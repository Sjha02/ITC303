package violet.beans;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.faces.bean.ManagedBean;
import javax.faces.bean.ManagedProperty;
import javax.faces.bean.RequestScoped;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.TypedQuery;

import violet.jpa.FactoryManager;
import violet.jpa.Game;
import violet.jpa.Genre;
import violet.jpa.Paginator;
import violet.filters.SearchFilter;

/**
 * Provides the list of games for the front page and browse page
 * @author somer
 */
@ManagedBean(name="gameListBean")
@RequestScoped
public class GameListBean {
	private static final int FRONT_PAGE_SIZE = 24;
	private static final int PAGE_SIZE = 15;
	private SearchFilter filter;
	
	private static Map<String, String> sortOptions;
	private static Map<String, String> platformOptions;

	@ManagedProperty(value="#{userBean}")
	private UserBean userBean;
	
	private int page = 1;
	
	private String searchQuery = "";
	private String sortQuery = "released";
	
	private String[] genreFilter;
	private String[] platformFilter;
	
	
	public UserBean getUserBean() {
		return userBean;
	}

	public void setUserBean(UserBean userBean) {
		this.userBean = userBean;
	}
	
	public int getPage() {
		return page;
	}
	
	public void setPage(int page) {
		this.page = page;
	}
	
	public String getSearchQuery() {
		return searchQuery;
	}
	
	public void setSearchQuery(String searchQuery) {
		this.searchQuery = searchQuery;
	}
	
	public String getSortQuery() {
		return sortQuery;
	}
	
	public void setSortQuery(String s) {
		if(getSortOptions().containsValue(s))
			this.sortQuery = s;
		else
			this.sortQuery = "released";
	}
	
	public Map<String, String> getSortOptions() {
		if(sortOptions == null) {
			sortOptions = new LinkedHashMap<>();
			sortOptions.put("New Releases", "released");
			sortOptions.put("Upcoming", "upcoming");
			sortOptions.put("Average Rating", "rating");
			sortOptions.put("Title A-Z", "az");
			sortOptions.put("Title Z-A", "za");
		}
		
		return sortOptions;
	}
	
	public Map<String, String> getPlatformOptions() {
		if(platformOptions == null) {
			platformOptions = new LinkedHashMap<>();
			platformOptions.put("Steam", "steam");
			platformOptions.put("Playstation", "playstation");
			platformOptions.put("Xbox", "xbox");
			platformOptions.put("Nintendo", "nintendo");
		}
		
		return platformOptions;
	}
	
	public String[] getPlatformFilter() {
		if(platformFilter == null)
			return new String[0];
		
		return platformFilter;
	}
	
	public void setPlatformFilter(String[] platformFilter) {
		this.platformFilter = platformFilter;
	}
	
	public String[] getGenreFilter() {
		if(genreFilter == null)
			return new String[0];
		
		return genreFilter;
	}
	
	public void setGenreFilter(String[] genreFilter) {
		this.genreFilter = genreFilter;
	}
	
	public List<Genre> getGenreChoices() {
		FactoryManager.pullCommonEM();
		try {
			return new ArrayList<Genre>(Genre.getGenres(FactoryManager.getCommonEM(), false));
		} finally {
			FactoryManager.popCommonEM();
		}
	}
	
	public String search() {
		setPage(1);
		
		return "pretty:browse-games";
	}
	
	/**
	 * Returns a {@link Paginator} object containing the list of games
	 * @param page the page number
	 * @param length length of a page (i.e. 25 games)
	 * @param releasedOnly if true, only returns games that have a release date before the current date
	 * @param search filters for a substring in a game's name
	 * @return a {@link Paginator} object containing the list of games
	 */
	public Paginator<Game> getPaginatedGames(int page, Integer length, boolean releasedOnly, String search) {
		EntityManager em = FactoryManager.pullCommonEM();
		try {
			search = search != null ? search.toLowerCase() : "";
			search = search.isEmpty() ? "" : "%" + search.replace("%", "\\%").replace("_", "\\_") + "%";
			
			filter = new SearchFilter(releasedOnly || sortQuery.equals("released"), search, sortQuery, getPlatformFilter(), getGenreFilter().length>0);
			String queryStart = filter.queryStart();
			String queryFilter = filter.queryWhere();
			String queryOrder = filter.queryOrder();
			
			TypedQuery<Game> tq = em.createQuery(queryStart + queryFilter + queryOrder, Game.class);
			if(!search.isEmpty())
				tq.setParameter("searchQuery", search);
			if(getGenreFilter().length>0) {
				tq.setParameter("genres", Arrays.asList(genreFilter));
			}
			List<Game> list = tq
					.setFirstResult((page-1) * length)
					.setMaxResults(length)
					.getResultList();
			
			TypedQuery<Long> ctq = em.createQuery("SELECT COUNT(g) FROM Game g" + queryFilter, Long.class);
			if(!search.isEmpty())
				ctq.setParameter("searchQuery", search);
			if(getGenreFilter().length>0) {
				ctq.setParameter("genres", Arrays.asList(genreFilter));
			}
			Long count = ctq.getSingleResult();
			
			Logger.getLogger(this.getClass().getName()).log(Level.INFO, "Count " + count);
			
			Long pages = count/Long.valueOf(length.longValue());
			
			return new Paginator<Game>(page, length, pages.intValue() + 1, list); // We need to grab it first, or the finally below will close the em before we get the row
		} catch(NoResultException e) {
			List<Game> list = Collections.<Game>emptyList();
			return new Paginator<Game>(page, length, 0, list);
		} finally {
			FactoryManager.popCommonEM();
		}
	}
	
	public List<Game> getFrontPageGames() {
		return getPaginatedGames(1, FRONT_PAGE_SIZE, true, "").getItems(); 
	}
	
	public Paginator<Game> getPaginatedGames() {
		return getPaginatedGames(page, PAGE_SIZE, false, searchQuery);
	}
}
