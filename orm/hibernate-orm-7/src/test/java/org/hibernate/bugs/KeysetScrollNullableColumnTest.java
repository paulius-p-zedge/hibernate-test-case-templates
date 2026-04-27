package org.hibernate.bugs;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
 * Regression in Hibernate 7.2+: keyset scroll generates incorrect WHERE clauses
 * for nullable sort columns.
 * <p>
 * Expected (6.6.x): {@code WHERE (i.score < ? OR i.score IS NULL AND i.sort_order > ? ...)}
 * Actual (7.2+):     {@code WHERE (i.score IS NOT NULL OR i.score IS NULL AND i.sort_order > ? ...)}
 * <p>
 * The cursor value comparison is replaced with a null-existence check, breaking pagination.
 * In the original use case the nullable column comes from a LEFT JOIN (@OneToOne),
 * but the same issue applies to any nullable sort column.
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
	private static KeyedPage<Item> firstPage() {
		return Page.first(2).keyedBy((List<Order<? super Item>>) (List<?>) List.of(
				Order.desc(Item.class, "score"),
				Order.desc(Item.class, "sortOrder"),
				Order.desc(Item.class, "id")
		));
	}

	/**
	 * All items have NULL score. Backward pagination returns wrong results because
	 * the WHERE clause degenerates to {@code score IS NULL} matching all rows.
	 */
	@Test
	void keysetBackwardPaginationWithAllNullScores() {
		EntityManager em = entityManagerFactory.createEntityManager();
		em.getTransaction().begin();
		for (int i = 0; i < 6; i++) {
			Item item = new Item();
			item.id = UUID.randomUUID();
			item.title = "Item " + i;
			item.sortOrder = i;
			// score is left null
			em.persist(item);
		}
		em.getTransaction().commit();

		Session session = em.unwrap(Session.class);
		var query = session.createSelectionQuery("FROM KeysetNullItem", Item.class);

		// Forward through 3 pages of 2
		KeyedResultList<Item> page1 = query.getKeyedResultList(firstPage());
		assertEquals(2, page1.getResultList().size());
		assertNotNull(page1.getNextPage());

		KeyedResultList<Item> page2 = query.getKeyedResultList(page1.getNextPage());
		assertEquals(2, page2.getResultList().size());
		assertNotNull(page2.getNextPage());

		KeyedResultList<Item> page3 = query.getKeyedResultList(page2.getNextPage());
		assertEquals(2, page3.getResultList().size());

		// Backward: page 3 → page 2
		assertNotNull(page3.getPreviousPage(), "Page 3 should have previous page");
		KeyedResultList<Item> backPage2 = query.getKeyedResultList(page3.getPreviousPage());

		assertEquals(2, backPage2.getResultList().size(),
				"Backward page should contain exactly 2 items");
		assertEquals(
				page2.getResultList().stream().map(i -> i.id).toList(),
				backPage2.getResultList().stream().map(i -> i.id).toList(),
				"Backward page 2 should match forward page 2"
		);

		em.close();
	}

	/**
	 * Mixed null/non-null scores. Forward pagination may produce duplicates because
	 * {@code IS NOT NULL} matches all scored rows regardless of cursor value.
	 */
	@Test
	void keysetForwardPaginationWithMixedNullScores() {
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
			// score left null
			em.persist(item);
		}
		em.getTransaction().commit();

		Session session = em.unwrap(Session.class);
		var query = session.createSelectionQuery("FROM KeysetNullItem", Item.class);

		KeyedResultList<Item> page1 = query.getKeyedResultList(firstPage());
		assertEquals(2, page1.getResultList().size());

		KeyedResultList<Item> page2 = query.getKeyedResultList(page1.getNextPage());
		assertEquals(2, page2.getResultList().size());

		KeyedResultList<Item> page3 = query.getKeyedResultList(page2.getNextPage());
		assertEquals(2, page3.getResultList().size());

		// Verify no duplicates across pages
		var allIds = new java.util.HashSet<UUID>();
		page1.getResultList().forEach(i -> assertTrue(allIds.add(i.id), "Duplicate: " + i.title));
		page2.getResultList().forEach(i -> assertTrue(allIds.add(i.id), "Duplicate: " + i.title));
		page3.getResultList().forEach(i -> assertTrue(allIds.add(i.id), "Duplicate: " + i.title));
		assertEquals(6, allIds.size(), "All 6 items should appear exactly once across 3 pages");

		em.close();
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
