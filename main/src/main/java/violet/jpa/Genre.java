package violet.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.persistence.*;

/**
 * Stores the genres that games are assigned
 * @author somer
 */
@Entity
public class Genre {
	@Id
	private String identifier;
	
	private String name;
	
	@ManyToMany
	private List<Game> games;
	
	@ManyToMany(mappedBy="genres", cascade=CascadeType.PERSIST)
	private List<Characteristic> characteristics;
	
	public Genre() {
		games = new ArrayList<Game>();
		characteristics = new ArrayList<Characteristic>();
	}
	
	public Genre(String name) {
		this();
		this.identifier = name.toLowerCase();;
		this.name = name;
	}
	
	public String getIndentifier() {
		return identifier;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public void addGame(Game game) {
		if(games.contains(game))
			return;
		
		games.add(game);
		game.addGenre(this);
	}
	
	public List<Game> getGames() {
		return games;
	}
	
	public void addCharacteristic(Characteristic characteristic) {
		if(characteristics.contains(characteristic))
			return;
		
		characteristics.add(characteristic);
		characteristic.addGenre(this);
	}
	
	public List<Characteristic> getCharacteristics() {
		return characteristics;
	}
	
	/**
	 * @param name
	 * @param create
	 * @param em
	 * @return A Genre object with the given name. If create is true and the genre doesn't already exist, a new Genre object will be created and returned.
	 */
	public static Genre getGenre(String name, boolean create, EntityManager em) {
		try {
			TypedQuery<Genre> tq = em.createQuery("SELECT g FROM Genre g WHERE LOWER(g.identifier)=:identifier", Genre.class);
			return tq.setParameter("identifier", name.toLowerCase()).getSingleResult();
		} catch(NoResultException e) {
			if(create)
				return new Genre(name);
			return null;
		}
	}
	
	/**
	 * @param em
	 * @return A collection of all Genres saved in the database
	 */
	public static Collection<Genre> getGenres(EntityManager em) {
		try {
			TypedQuery<Genre> tq = em.createQuery("SELECT g FROM Genre g", Genre.class);
			return tq.getResultList();
		} catch(NoResultException e) {
			return Collections.emptyList();
		}
	}
}
