package org.hibernate.bugs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.JpaMetamodelEntityInformation;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keyset scroll via Spring Data JPA's {@code findBy().scroll()} generates
 * incorrect WHERE clauses for nullable LEFT JOIN sort columns.
 * <p>
 * Items with NULL sort values become unreachable during pagination.
 * Works correctly with Hibernate 6.6.x / Spring Data 3.x.
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

	private static final Sort SORT = Sort.by(
			Sort.Order.desc("score.value"),
			Sort.Order.desc("sortOrder"),
			Sort.Order.desc("id")
	);

	private Window<Item> scroll(SimpleJpaRepository<Item, UUID> repo, ScrollPosition position) {
		Specification<Item> spec = (root, query, cb) -> null;
		return repo.findBy(spec, q ->
				q.limit(2).sortBy(SORT).scroll(position));
	}

	private ScrollPosition positionAfter(EntityManager em, Item item) {
		var keys = new LinkedHashMap<String, Object>();
		ItemScore score = em.find(ItemScore.class, item.id);
		keys.put("score.value", score != null ? score.value : null);
		keys.put("sortOrder", item.sortOrder);
		keys.put("id", item.id);
		return ScrollPosition.forward(keys);
	}

	/**
	 * 6 items, no scores (LEFT JOIN produces NULL). Forward pagination loses items.
	 */
	@Test
	void allNullScores_paginationLosesItems() {
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

		var entityInfo = new JpaMetamodelEntityInformation<Item, UUID>(
				Item.class, em.getMetamodel(), em.getEntityManagerFactory().getPersistenceUnitUtil());
		var repo = new SimpleJpaRepository<Item, UUID>(entityInfo, em);

		Window<Item> page1 = scroll(repo, ScrollPosition.keyset());
		assertEquals(2, page1.getContent().size(), "Page 1 should have 2 items");
		assertTrue(page1.hasNext(), "Should have more pages");

		Window<Item> page2 = scroll(repo, positionAfter(em, page1.getContent().get(1)));
		assertEquals(2, page2.getContent().size(), "Page 2 should have 2 items");

		Window<Item> page3 = scroll(repo, positionAfter(em, page2.getContent().get(1)));
		assertEquals(2, page3.getContent().size(), "Page 3 should have 2 items");

		// Backward from page 3's first item
		var backKeys = new LinkedHashMap<String, Object>();
		var firstOfPage3 = page3.getContent().get(0);
		ItemScore backScore = em.find(ItemScore.class, firstOfPage3.id);
		backKeys.put("score.value", backScore != null ? backScore.value : null);
		backKeys.put("sortOrder", firstOfPage3.sortOrder);
		backKeys.put("id", firstOfPage3.id);
		ScrollPosition backPosition = ScrollPosition.backward(backKeys);

		System.out.println("=== Backward scroll ===");
		Window<Item> backPage = scroll(repo, backPosition);
		assertEquals(2, backPage.getContent().size(),
				"Backward page should have 2 items matching page 2");

		em.close();
	}

	/**
	 * 3 scored + 3 unscored items. Only scored items reachable via pagination.
	 */
	@Test
	void mixedNullScores_paginationLosesUnscoredItems() {
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

		var entityInfo = new JpaMetamodelEntityInformation<Item, UUID>(
				Item.class, em.getMetamodel(), em.getEntityManagerFactory().getPersistenceUnitUtil());
		var repo = new SimpleJpaRepository<Item, UUID>(entityInfo, em);

		var allItems = new ArrayList<Item>();
		ScrollPosition position = ScrollPosition.keyset();
		while (true) {
			Window<Item> page = scroll(repo, position);
			if (page.getContent().isEmpty()) break;
			allItems.addAll(page.getContent());
			var last = page.getContent().get(page.getContent().size() - 1);
			position = positionAfter(em, last);
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
