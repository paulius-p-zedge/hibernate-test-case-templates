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
 * Items with NULL sort values become unreachable during pagination.
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
		return repo.findBy(spec, q -> q.limit(2).sortBy(SORT).scroll(position));
	}

	private ScrollPosition positionAfter(EntityManager em, Item item) {
		var keys = new LinkedHashMap<String, Object>();
		ItemScore score = em.find(ItemScore.class, item.id);
		keys.put("score.value", score != null ? score.value : null);
		keys.put("sortOrder", item.sortOrder);
		keys.put("id", item.id);
		return ScrollPosition.forward(keys);
	}

	private ScrollPosition positionBefore(EntityManager em, Item item) {
		var keys = new LinkedHashMap<String, Object>();
		ItemScore score = em.find(ItemScore.class, item.id);
		keys.put("score.value", score != null ? score.value : null);
		keys.put("sortOrder", item.sortOrder);
		keys.put("id", item.id);
		return ScrollPosition.backward(keys);
	}

	private List<UUID> ids(Window<Item> window) {
		return window.getContent().stream().map(i -> i.id).toList();
	}

	private List<UUID> ids(List<Item> items) {
		return items.stream().map(i -> i.id).toList();
	}

	/**
	 * 6 items, no scores. Forward + backward pagination.
	 * Sort: score DESC NULLS LAST (all null), sortOrder DESC, id DESC.
	 * Expected order: Item 5, 4, 3, 2, 1, 0 (by sortOrder DESC).
	 */
	@Test
	void allNullScores_forwardAndBackwardPagination() {
		EntityManager em = entityManagerFactory.createEntityManager();
		em.getTransaction().begin();
		var items = new ArrayList<Item>();
		for (int i = 0; i < 6; i++) {
			Item item = new Item();
			item.id = UUID.randomUUID();
			item.title = "Item " + i;
			item.sortOrder = i;
			em.persist(item);
			items.add(item);
		}
		em.getTransaction().commit();

		// Expected order: sortOrder DESC (all scores null, so secondary key decides)
		var expectedOrder = new ArrayList<>(items);
		java.util.Collections.reverse(expectedOrder); // [Item 5, 4, 3, 2, 1, 0]
		var expectedPage1Ids = ids(expectedOrder.subList(0, 2));
		var expectedPage2Ids = ids(expectedOrder.subList(2, 4));
		var expectedPage3Ids = ids(expectedOrder.subList(4, 6));

		var entityInfo = new JpaMetamodelEntityInformation<Item, UUID>(
				Item.class, em.getMetamodel(), em.getEntityManagerFactory().getPersistenceUnitUtil());
		var repo = new SimpleJpaRepository<Item, UUID>(entityInfo, em);

		// Forward page 1
		Window<Item> page1 = scroll(repo, ScrollPosition.keyset());
		assertEquals(expectedPage1Ids, ids(page1), "Forward page 1 items");

		// Forward page 2
		Window<Item> page2 = scroll(repo, positionAfter(em, page1.getContent().get(1)));
		assertEquals(expectedPage2Ids, ids(page2), "Forward page 2 items");

		// Forward page 3
		Window<Item> page3 = scroll(repo, positionAfter(em, page2.getContent().get(1)));
		assertEquals(expectedPage3Ids, ids(page3), "Forward page 3 items");

		// Backward from page 3's first item → should return page 2
		Window<Item> backFromPage3 = scroll(repo, positionBefore(em, page3.getContent().get(0)));
		assertEquals(expectedPage2Ids, ids(backFromPage3),
				"Backward from page 3 should return page 2's items");

		// Backward from page 2's first item → should return page 1
		Window<Item> backFromPage2 = scroll(repo, positionBefore(em, backFromPage3.getContent().get(0)));
		assertEquals(expectedPage1Ids, ids(backFromPage2),
				"Backward from page 2 should return page 1's items");

		em.close();
	}

	/**
	 * 3 scored + 3 unscored items sorted by score DESC NULLS LAST.
	 * Expected order: Scored 0 (100), Scored 1 (99), Scored 2 (98), Unscored 2, 1, 0.
	 * All 6 should be reachable via forward pagination with no duplicates.
	 */
	@Test
	void mixedNullScores_forwardPaginationLosesUnscoredItems() {
		EntityManager em = entityManagerFactory.createEntityManager();
		em.getTransaction().begin();
		var scoredItems = new ArrayList<Item>();
		var unscoredItems = new ArrayList<Item>();
		for (int i = 0; i < 3; i++) {
			Item item = new Item();
			item.id = UUID.randomUUID();
			item.title = "Scored " + i;
			item.sortOrder = 10 + i;
			em.persist(item);
			scoredItems.add(item);

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
			unscoredItems.add(item);
		}
		em.getTransaction().commit();

		// Expected: scored first (by score DESC), then unscored (by sortOrder DESC)
		var expectedAllIds = new ArrayList<UUID>();
		expectedAllIds.addAll(ids(scoredItems));                // Scored 0 (100), 1 (99), 2 (98)
		var unscoredReversed = new ArrayList<>(unscoredItems);
		java.util.Collections.reverse(unscoredReversed);
		expectedAllIds.addAll(ids(unscoredReversed));           // Unscored 2 (22), 1 (21), 0 (20)

		var entityInfo = new JpaMetamodelEntityInformation<Item, UUID>(
				Item.class, em.getMetamodel(), em.getEntityManagerFactory().getPersistenceUnitUtil());
		var repo = new SimpleJpaRepository<Item, UUID>(entityInfo, em);

		var actualAllIds = new ArrayList<UUID>();
		ScrollPosition position = ScrollPosition.keyset();
		while (true) {
			Window<Item> page = scroll(repo, position);
			if (page.getContent().isEmpty()) break;
			actualAllIds.addAll(ids(page));
			position = positionAfter(em, page.getContent().get(page.getContent().size() - 1));
		}

		assertEquals(expectedAllIds, actualAllIds,
				"All 6 items in correct order: scored first (by score DESC), then unscored (by sortOrder DESC)");

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
