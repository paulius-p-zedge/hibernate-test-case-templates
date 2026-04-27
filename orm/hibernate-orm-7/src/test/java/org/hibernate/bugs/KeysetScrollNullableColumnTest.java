package org.hibernate.bugs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

	/**
	 * 6 items, no scores. Forward + backward pagination with full ID assertions.
	 */
	@Test
	void allNullScores_forwardAndBackwardPagination() {
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

		// Forward: 3 pages of 2
		Window<Item> page1 = scroll(repo, ScrollPosition.keyset());
		assertEquals(2, page1.getContent().size(), "Page 1 size");

		Window<Item> page2 = scroll(repo, positionAfter(em, page1.getContent().get(1)));
		assertEquals(2, page2.getContent().size(), "Page 2 size");

		Window<Item> page3 = scroll(repo, positionAfter(em, page2.getContent().get(1)));
		assertEquals(2, page3.getContent().size(), "Page 3 size");

		// All 6 unique items across forward pages
		var allForwardIds = new HashSet<UUID>();
		allForwardIds.addAll(ids(page1));
		allForwardIds.addAll(ids(page2));
		allForwardIds.addAll(ids(page3));
		assertEquals(6, allForwardIds.size(), "Forward should cover all 6 items without duplicates");

		// Backward from page 3's first item should return page 2's items
		Window<Item> backFromPage3 = scroll(repo, positionBefore(em, page3.getContent().get(0)));
		assertEquals(ids(page2), ids(backFromPage3),
				"Backward from page 3 should return page 2's items");

		// Backward from page 2's first item should return page 1's items
		Window<Item> backFromPage2 = scroll(repo, positionBefore(em, backFromPage3.getContent().get(0)));
		assertEquals(ids(page1), ids(backFromPage2),
				"Backward from page 2 should return page 1's items");

		em.close();
	}

	/**
	 * 3 scored + 3 unscored items. All 6 should be reachable with no duplicates.
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
		var allIds = new HashSet<UUID>();
		ScrollPosition position = ScrollPosition.keyset();
		while (true) {
			Window<Item> page = scroll(repo, position);
			if (page.getContent().isEmpty()) break;
			for (Item item : page.getContent()) {
				assertTrue(allIds.add(item.id), "Duplicate item: " + item.title);
			}
			allItems.addAll(page.getContent());
			position = positionAfter(em, page.getContent().get(page.getContent().size() - 1));
		}

		assertEquals(6, allItems.size(),
				"All 6 items should be reachable, but only found: "
						+ allItems.stream().map(i -> i.title).collect(Collectors.joining(", ")));
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
