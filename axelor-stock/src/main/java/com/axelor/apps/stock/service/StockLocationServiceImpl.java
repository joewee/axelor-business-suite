/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2018 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.apps.stock.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.ProductService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockLocationLine;
import com.axelor.apps.stock.db.StockRules;
import com.axelor.apps.stock.db.repo.StockLocationLineRepository;
import com.axelor.apps.stock.db.repo.StockLocationRepository;
import com.axelor.apps.stock.db.repo.StockRulesRepository;
import com.axelor.apps.stock.service.config.StockConfigService;
import com.axelor.db.JPA;
import com.axelor.exception.AxelorException;
import com.axelor.inject.Beans;
import com.google.inject.Inject;

public class StockLocationServiceImpl implements StockLocationService {
	
	protected StockLocationRepository stockLocationRepo;
	
	protected StockLocationLineService stockLocationLineService;
	
	protected ProductRepository productRepo;
	
	Set<Long> locationIdSet= new HashSet<>();

	@Inject
	public StockLocationServiceImpl(StockLocationRepository stockLocationRepo, StockLocationLineService stockLocationLineService, ProductRepository productRepo) {
		this.stockLocationRepo = stockLocationRepo;
		this.stockLocationLineService = stockLocationLineService;
		this.productRepo = productRepo;
	}

	public StockLocation getLocation(Company company) {
		try {
			StockConfigService stockConfigService = Beans.get(StockConfigService.class);
			StockConfig stockConfig = stockConfigService.getStockConfig(company);
			return stockConfig.getDefaultLocation();
		} catch (AxelorException e) {
			return null;
		}
	}

	public List<StockLocation> getNonVirtualLocations() {
		return stockLocationRepo.all().filter("self.typeSelect != ?1", StockLocationRepository.TYPE_VIRTUAL).fetch();
	}
	
	@Override
	public BigDecimal getQty(Long productId, Long locationId, String qtyType) {
		if (productId != null) {
			if (locationId == null) {
				List<StockLocation> locations = getNonVirtualLocations();
				if (!locations.isEmpty()) {
					BigDecimal qty = BigDecimal.ZERO;
					for (StockLocation location : locations) {
						StockLocationLine stockLocationLine = stockLocationLineService.getStockLocationLine(stockLocationRepo.find(location.getId()), productRepo.find(productId));
						
						if (stockLocationLine != null) {
							qty = qty.add(qtyType.equals("real") ? stockLocationLine.getCurrentQty() : stockLocationLine.getFutureQty());
						}
					}
					return qty;
				}
			} else {
				StockLocationLine stockLocationLine = stockLocationLineService.getStockLocationLine(stockLocationRepo.find(locationId), productRepo.find(productId));
				
				if (stockLocationLine != null) {
					return qtyType.equals("real") ? stockLocationLine.getCurrentQty() : stockLocationLine.getFutureQty();
				}
			}
		}
		
		return null;
	}

	@Override
	public BigDecimal getRealQty(Long productId, Long locationId) {
		return getQty(productId, locationId, "real");
	}

	@Override
	public BigDecimal getFutureQty(Long productId, Long locationId) {
		return getQty(productId, locationId, "future");
	}

	@Override
	public void computeAvgPriceForProduct(Product product) {
		Long productId = product.getId();
		String query = "SELECT new list(self.id, self.avgPrice, self.currentQty) FROM LocationLine as self "
				+ "WHERE self.product.id = " + productId + " AND self.location.typeSelect != "
				+ StockLocationRepository.TYPE_VIRTUAL;
		int scale = Beans.get(AppBaseService.class).getNbDecimalDigitForUnitPrice();
		BigDecimal productAvgPrice = BigDecimal.ZERO;
		BigDecimal qtyTot = BigDecimal.ZERO;
		List<List<Object>> results = JPA.em().createQuery(query).getResultList();
		if (results.isEmpty()) {
			return;
		}
		for (List<Object> result : results) {
			BigDecimal avgPrice = (BigDecimal) result.get(1);
			BigDecimal qty = (BigDecimal) result.get(2);
			productAvgPrice = productAvgPrice.add(avgPrice.multiply(qty));
			qtyTot = qtyTot.add(qty);
		}
		if (qtyTot.compareTo(BigDecimal.ZERO) == 0) {
			return;
		}
		productAvgPrice = productAvgPrice.divide(qtyTot, scale, BigDecimal.ROUND_HALF_UP);
		product.setAvgPrice(productAvgPrice);
		if (product.getCostTypeSelect() == ProductRepository.COST_TYPE_AVERAGE_PRICE) {
		    product.setCostPrice(productAvgPrice);
			if (product.getAutoUpdateSalePrice()) {
				Beans.get(ProductService.class).updateSalePrice(product);
			}
		}
		productRepo.save(product);
	}

	public List<Long> getBadLocationLineId() {

		List<StockLocationLine> stockLocationLineList = Beans.get(StockLocationLineRepository.class)
				.all().filter("self.location.typeSelect = 1 OR self.location.typeSelect = 2").fetch();

		List<Long> idList = new ArrayList<>();

		for (StockLocationLine stockLocationLine : stockLocationLineList) {
			StockRules stockRules = Beans.get(StockRulesRepository.class).all()
					.filter("self.location = ?1 AND self.product = ?2", stockLocationLine.getStockLocation(), stockLocationLine.getProduct()).fetchOne();
			if (stockRules != null
					&& stockLocationLine.getFutureQty().compareTo(stockRules.getMinQty()) < 0) {
				idList.add(stockLocationLine.getId());
			}
		}

		if (idList.isEmpty()) {
			idList.add(0L);
		}

		return idList;
	}
	
	private void findLocationIds(List<StockLocation> childLocations) {
		
		Long id = null;
		
		childLocations = Beans.get(StockLocationRepository.class).all().filter("self.parentLocation IN ?", childLocations).fetch();
			
		Iterator<StockLocation> it = childLocations.iterator();
		
		while (it.hasNext()) {

			id = it.next().getId();
			if(locationIdSet.contains(id)) {
				it.remove();
			} else {
				locationIdSet.add(id);
			}
		}

		if(!childLocations.isEmpty()) 
			findLocationIds(childLocations);
	}
	
	@Override
	public Set<Long> getContentLocationIds(StockLocation location) {
		
		List<StockLocation> locations = new ArrayList<>();

		if(location != null) {
			locations.add(location);
			locationIdSet.add(location.getId());
			findLocationIds(locations);
		} else {
			locationIdSet.add(0l);
		}
		
		return locationIdSet;
	}
}