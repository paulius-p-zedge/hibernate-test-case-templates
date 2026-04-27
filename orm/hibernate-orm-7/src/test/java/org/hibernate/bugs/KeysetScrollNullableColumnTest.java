package org.hibernate.bugs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Persistence;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.query.KeysetScrollSpecification;
import org.springframework.data.jpa.repository.support.JpaEntityInformation;
import org.springframework.data.jpa.repository.support.JpaMetamodelEntityInformation;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates a bug in Spring Data JPA 4.x {@link KeysetScrollSpecification}
 * where keyset scroll generates incorrect WHERE clauses for nullable LEFT JOIN
 * sort columns when null ordering is not set explicitly on {@link Sort.Order}
 * but is handled by Hibernate's {@code default_null_ordering=last}.
 * <p>
 * {@code KeysetScrollSpecification.CriteriaBuilderStrategy.compare()} checks
 * {@code isNullsLast(order)} which returns false when {@code NullHandling} is
 * {@code NATIVE}, even though Hibernate IS applying NULLS LAST via its setting.
 * <p>
 * Expected WHERE (and what Hibernate 6.6.x / Spring Data 3.x generated):
 * <pre>
 *   (s.score_value &lt; ?
 *    OR s.score_value IS NULL AND i.sort_order &lt; ?
 *    OR s.score_value IS NULL AND i.sort_order = ? AND i.id &lt; ?)
 * </pre>
 * Actual WHERE (Spring Data 4.x):
 * <pre>
 *   (s.score_value IS NOT NULL
 *    OR s.score_value IS NULL AND i.sort_order &lt; ?
 *    OR s.score_value IS NULL AND i.sort_order = ? AND i.id &lt; ?)
 * </pre>
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

	// No explicit nullsLast() — null ordering is handled by Hibernate's
	// default_null_ordering=last setting, which Spring Data doesn't know about.
	private static final Sort SORT = Sort.by(
			Sort.Order.desc("score.value"),
			Sort.Order.desc("sortOrder"),
			Sort.Order.desc("id")
	);

	/**
	 * Builds and executes a keyset-scrolled Criteria query using
	 * {@link KeysetScrollSpecification#createPredicate} — the same code path as
	 * Spring Data's {@code JpaSpecificationExecutor.findBy(...).scroll(position)}.
	 */
	private List<Item> scrollQuery(EntityManager em, KeysetScrollPosition position, int pageSize) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<Item> cq = cb.createQuery(Item.class);
		Root<Item> root = cq.from(Item.class);
		root.fetch("score", JoinType.LEFT);
		var scoreJoin = root.join("score", JoinType.LEFT);
		cq.select(root);

		// Use KeysetScrollSpecification.createPredicate — the exact Spring Data code path.
		JpaEntityInformation<Item, ?> entityInfo = new JpaMetamodelEntityInformation<>(
				Item.class, em.getMetamodel(), em.getEntityManagerFactory().getPersistenceUnitUtil());
		var spec = new KeysetScrollSpecification<Item>(position, SORT, entityInfo);
		Predicate predicate = spec.createPredicate(root, cb);
		if (predicate != null) {
			cq.where(predicate);
		}

		// Apply sort using the explicit LEFT JOIN for score.value
		cq.orderBy(
				cb.desc(scoreJoin.get("value")),
				cb.desc(root.get("sortOrder")),
				cb.desc(root.get("id"))
		);

		return em.createQuery(cq)
				.setMaxResults(pageSize)
				.getResultList();
	}

	private KeysetScrollPosition positionAfter(EntityManager em, Item item) {
		var keys = new LinkedHashMap<String, Object>();
		// Explicitly load score to avoid lazy proxy issues
		ItemScore score = em.find(ItemScore.class, item.id);
		keys.put("score.value", score != null ? score.value : null);
		keys.put("sortOrder", item.sortOrder);
		keys.put("id", item.id);
		return ScrollPosition.forward(keys);
	}

	/**
	 * 6 items with no scores (LEFT JOIN produces NULL).
	 * Page 1 returns 2 items. Page 2 should return 2 but returns 0.
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
			em.persist(item);
		}
		em.getTransaction().commit();

		List<Item> page1 = scrollQuery(em, ScrollPosition.keyset(), 2);
		assertEquals(2, page1.size(), "Page 1 should have 2 items");

		List<Item> page2 = scrollQuery(em, positionAfter(em, page1.get(1)), 2);
		assertEquals(2, page2.size(),
				"Page 2 should have 2 items — KeysetScrollSpecification generates incorrect " +
						"keyset predicate when cursor's score is NULL");

		em.close();
	}

	/**
	 * 3 scored + 3 unscored items. Only scored items are reachable via pagination.
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
			em.persist(item);

			ItemScore score = new ItemScore();
			score.itemId = item.id;
			score.value = BigDecimal.valueOf(100 - i);
			score.item = item;
			em.persist(score);
		}
		for (int i = 0; i < 3; i++) {
			Item item = new Item();
			item.id = UUID.randomUUID();
			item.title = "Unscored " + i;
			item.sortOrder = 20 + i;
			em.persist(item);
		}
		em.getTransaction().commit();

		var allItems = new ArrayList<Item>();
		KeysetScrollPosition position = ScrollPosition.keyset();
		while (true) {
			List<Item> page = scrollQuery(em, position, 2);
			if (page.isEmpty()) break;
			allItems.addAll(page);
			position = positionAfter(em, page.get(page.size() - 1));
		}

		assertEquals(6, allItems.size(),
				"All 6 items should be reachable, but only found: "
						+ allItems.stream().map(i -> i.title).toList());

		em.close();
	}

	@Entity(name = "Item")
	@Table(name = "item")
	public static class Item {
		@Id
		public UUID id;
		public String title;
		@Column(name = "sort_order")
		public int sortOrder;
		@OneToOne(fetch = FetchType.LAZY, mappedBy = "item")
		public ItemScore score;
	}

	@Entity(name = "ItemScore")
	@Table(name = "item_score")
	public static class ItemScore {
		@Id
		@Column(name = "item_id")
		public UUID itemId;
		@Column(name = "score_value")
		public BigDecimal value;
		@OneToOne(fetch = FetchType.LAZY)
		@PrimaryKeyJoinColumn
		public Item item;
	}
}
