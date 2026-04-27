package org.hibernate.bugs;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import jakarta.persistence.Persistence;
import jakarta.persistence.Table;

import org.hibernate.Session;
import org.hibernate.query.Order;
import org.hibernate.query.Page;
import org.hibernate.query.KeyedPage;
import org.hibernate.query.KeyedResultList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression in Hibernate 7.x: keyset pagination generates incorrect WHERE clauses
 * for nullable sort columns.
 * <p>
 * When the first sort key is nullable (e.g. a score that can be NULL), the keyset
 * WHERE clause replaces cursor value comparisons with null-existence checks:
 * <ul>
 *   <li>Forward: generates {@code score IS NOT NULL} instead of {@code score < ?}</li>
 *   <li>Backward: generates {@code score IS NULL} instead of {@code score > ?}</li>
 * </ul>
 * This causes items to be lost or duplicated across pages.
 * <p>
 * Works correctly in Hibernate 6.6.x.
 */
class KeysetScrollNullableColumnTest {

	private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void init() {
		entityManagerFactory = Persistence.createEntityManagerFactory("templatePU");
	}

	@AfterEach
	void destroy() {
		entityManagerFactory.close();
	}

	@SuppressWarnings("unchecked")
	private static KeyedPage<Item> firstPage(int size) {
		return Page.first(size).keyedBy((List<Order<? super Item>>) (List<?>) List.of(
				Order.desc(Item.class, "score"),
				Order.desc(Item.class, "sortOrder"),
				Order.desc(Item.class, "id")
		));
	}

	/**
	 * 6 items, all with NULL score, paginated 2-at-a-time.
	 * Expected: 3 pages of 2 items each, all 6 items covered.
	 * Actual: page 2 returns 0 items — the keyset WHERE clause fails
	 * to find any rows after the cursor when all scores are NULL.
	 */
	@Test
	void allNullScores_forwardPaginationLosesItems() {
		EntityManager em = entityManagerFactory.createEntityManager();
		em.getTransaction().begin();
		for (int i = 0; i < 6; i++) {
			Item item = new Item();
			item.id = UUID.randomUUID();
			item.title = "Item " + i;
			item.sortOrder = i;
			// score left NULL
			em.persist(item);
		}
		em.getTransaction().commit();

		Session session = em.unwrap(Session.class);
		var query = session.createSelectionQuery("FROM KeysetNullItem", Item.class);

		KeyedResultList<Item> page1 = query.getKeyedResultList(firstPage(2));
		assertEquals(2, page1.getResultList().size(), "Page 1 should have 2 items");

		KeyedResultList<Item> page2 = query.getKeyedResultList(page1.getNextPage());
		assertEquals(2, page2.getResultList().size(),
				"Page 2 should have 2 items — but keyset WHERE clause returns 0 when all scores are NULL");

		em.close();
	}

	/**
	 * 3 scored + 3 unscored items, sorted by score DESC NULLS LAST.
	 * Expected: 3 pages of 2, all 6 items covered with no duplicates.
	 * Actual: only 3 items are returned across all pages — the unscored items are lost.
	 */
	@Test
	void mixedNullScores_forwardPaginationLosesUnscoredItems() {
		EntityManager em = entityManagerFactory.createEntityManager();
		em.getTransaction().begin();
		for (int i = 0; i < 3; i++) {
			Item item = new Item();
			item.id = UUID.randomUUID();
			item.title = "Scored " + i;
			item.sortOrder = 10 + i;
			item.score = BigDecimal.valueOf(100 - i);
			em.persist(item);
		}
		for (int i = 0; i < 3; i++) {
			Item item = new Item();
			item.id = UUID.randomUUID();
			item.title = "Unscored " + i;
			item.sortOrder = 20 + i;
			// score left NULL
			em.persist(item);
		}
		em.getTransaction().commit();

		Session session = em.unwrap(Session.class);
		var query = session.createSelectionQuery("FROM KeysetNullItem", Item.class);

		var allItems = new java.util.ArrayList<Item>();
		KeyedPage<Item> nextPage = firstPage(2);
		while (nextPage != null) {
			KeyedResultList<Item> page = query.getKeyedResultList(nextPage);
			allItems.addAll(page.getResultList());
			nextPage = page.getNextPage();
		}

		assertEquals(6, allItems.size(),
				"All 6 items should be reachable via forward pagination, but only found: "
						+ allItems.stream().map(i -> i.title).collect(Collectors.joining(", ")));
	}

	@Entity(name = "KeysetNullItem")
	@Table(name = "keyset_null_item")
	public static class Item {
		@Id
		public UUID id;
		public String title;
		@Column(name = "sort_order")
		public int sortOrder;
		@Column(nullable = true)
		public BigDecimal score;
	}
}
