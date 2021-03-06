/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2019 Axelor (<http://axelor.com>).
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
package com.axelor.apps.production.service.manuforder;

import com.axelor.apps.base.db.AppSupplychain;
import com.axelor.apps.base.db.CancelReason;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.ProductService;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.message.db.Template;
import com.axelor.apps.message.service.TemplateMessageService;
import com.axelor.apps.production.db.ManufOrder;
import com.axelor.apps.production.db.OperationOrder;
import com.axelor.apps.production.db.repo.CostSheetRepository;
import com.axelor.apps.production.db.repo.ManufOrderRepository;
import com.axelor.apps.production.db.repo.OperationOrderRepository;
import com.axelor.apps.production.db.repo.ProductionConfigRepository;
import com.axelor.apps.production.exceptions.IExceptionMessage;
import com.axelor.apps.production.service.app.AppProductionService;
import com.axelor.apps.production.service.costsheet.CostSheetService;
import com.axelor.apps.production.service.operationorder.OperationOrderWorkflowService;
import com.axelor.apps.supplychain.service.app.AppSupplychainService;
import com.axelor.exception.AxelorException;
import com.axelor.exception.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;

public class ManufOrderWorkflowService {
  protected OperationOrderWorkflowService operationOrderWorkflowService;
  protected OperationOrderRepository operationOrderRepo;
  protected ManufOrderStockMoveService manufOrderStockMoveService;
  protected ManufOrderRepository manufOrderRepo;

  @Inject
  public ManufOrderWorkflowService(
      OperationOrderWorkflowService operationOrderWorkflowService,
      OperationOrderRepository operationOrderRepo,
      ManufOrderStockMoveService manufOrderStockMoveService,
      ManufOrderRepository manufOrderRepo) {
    this.operationOrderWorkflowService = operationOrderWorkflowService;
    this.operationOrderRepo = operationOrderRepo;
    this.manufOrderStockMoveService = manufOrderStockMoveService;
    this.manufOrderRepo = manufOrderRepo;
  }

  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public ManufOrder plan(ManufOrder manufOrder) throws AxelorException {
    ManufOrderService manufOrderService = Beans.get(ManufOrderService.class);

    if (Beans.get(SequenceService.class)
        .isEmptyOrDraftSequenceNumber(manufOrder.getManufOrderSeq())) {
      manufOrder.setManufOrderSeq(manufOrderService.getManufOrderSeq(manufOrder));
    }

    if (CollectionUtils.isEmpty(manufOrder.getOperationOrderList())) {
      manufOrderService.preFillOperations(manufOrder);
    }
    if (!manufOrder.getIsConsProOnOperation()
        && CollectionUtils.isEmpty(manufOrder.getToConsumeProdProductList())) {
      manufOrderService.createToConsumeProdProductList(manufOrder);
    }

    if (CollectionUtils.isEmpty(manufOrder.getToProduceProdProductList())) {
      manufOrderService.createToProduceProdProductList(manufOrder);
    }

    if (manufOrder.getPlannedStartDateT() == null) {
      manufOrder.setPlannedStartDateT(
          Beans.get(AppProductionService.class).getTodayDateTime().toLocalDateTime());
    }

    for (OperationOrder operationOrder : getSortedOperationOrderList(manufOrder)) {
      operationOrderWorkflowService.plan(operationOrder);
    }

    manufOrder.setPlannedEndDateT(this.computePlannedEndDateT(manufOrder));

    if (manufOrder.getBillOfMaterial() != null) {
      manufOrder.setUnit(manufOrder.getBillOfMaterial().getUnit());
    }

    if (!manufOrder.getIsConsProOnOperation()) {
      manufOrderStockMoveService.createToConsumeStockMove(manufOrder);
    }

    manufOrderStockMoveService.createToProduceStockMove(manufOrder);
    manufOrder.setStatusSelect(ManufOrderRepository.STATUS_PLANNED);
    manufOrder.setCancelReason(null);
    manufOrder.setCancelReasonStr(null);

    return manufOrderRepo.save(manufOrder);
  }

  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public void start(ManufOrder manufOrder) throws AxelorException {

    manufOrder.setRealStartDateT(
        Beans.get(AppProductionService.class).getTodayDateTime().toLocalDateTime());

    int beforeOrAfterConfig = manufOrder.getProdProcess().getStockMoveRealizeOrderSelect();
    if (beforeOrAfterConfig == ProductionConfigRepository.REALIZE_START) {
      manufOrder.addInStockMoveListItem(
          manufOrderStockMoveService.realizeStockMovesAndCreateOneEmpty(
              manufOrder, manufOrder.getInStockMoveList()));
    }
    manufOrder.setStatusSelect(ManufOrderRepository.STATUS_IN_PROGRESS);
    manufOrderRepo.save(manufOrder);
  }

  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public void pause(ManufOrder manufOrder) {
    if (manufOrder.getOperationOrderList() != null) {
      for (OperationOrder operationOrder : manufOrder.getOperationOrderList()) {
        if (operationOrder.getStatusSelect() == OperationOrderRepository.STATUS_IN_PROGRESS) {
          operationOrderWorkflowService.pause(operationOrder);
        }
      }
    }

    manufOrder.setStatusSelect(ManufOrderRepository.STATUS_STANDBY);
    manufOrderRepo.save(manufOrder);
  }

  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public void resume(ManufOrder manufOrder) {
    if (manufOrder.getOperationOrderList() != null) {
      for (OperationOrder operationOrder : manufOrder.getOperationOrderList()) {
        if (operationOrder.getStatusSelect() == OperationOrderRepository.STATUS_STANDBY) {
          operationOrderWorkflowService.resume(operationOrder);
        }
      }
    }

    manufOrder.setStatusSelect(ManufOrderRepository.STATUS_IN_PROGRESS);
    manufOrderRepo.save(manufOrder);
  }

  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public void finish(ManufOrder manufOrder) throws AxelorException {
    if (manufOrder.getOperationOrderList() != null) {
      for (OperationOrder operationOrder : manufOrder.getOperationOrderList()) {
        if (operationOrder.getStatusSelect() != OperationOrderRepository.STATUS_FINISHED) {
          if (operationOrder.getStatusSelect() != OperationOrderRepository.STATUS_IN_PROGRESS
              && operationOrder.getStatusSelect() != OperationOrderRepository.STATUS_STANDBY) {
            operationOrderWorkflowService.start(operationOrder);
          }

          operationOrderWorkflowService.finish(operationOrder);
        }
      }
    }

    // create cost sheet
    Beans.get(CostSheetService.class)
        .computeCostPrice(
            manufOrder,
            CostSheetRepository.CALCULATION_END_OF_PRODUCTION,
            Beans.get(AppBaseService.class).getTodayDate());

    // update price in product
    Product product = manufOrder.getProduct();
    if (product.getRealOrEstimatedPriceSelect() == ProductRepository.PRICE_METHOD_FORECAST) {
      product.setLastProductionPrice(manufOrder.getBillOfMaterial().getCostPrice());
    } else if (product.getRealOrEstimatedPriceSelect() == ProductRepository.PRICE_METHOD_REAL) {
      BigDecimal costPrice = computeOneUnitProductionPrice(manufOrder);
      if (costPrice.signum() != 0) {
        product.setLastProductionPrice(costPrice);
      }
    } else {
      // default value is forecast
      product.setRealOrEstimatedPriceSelect(ProductRepository.PRICE_METHOD_FORECAST);
      product.setLastProductionPrice(manufOrder.getBillOfMaterial().getCostPrice());
    }

    // update costprice in product
    if (product.getCostTypeSelect() == ProductRepository.COST_TYPE_LAST_PRODUCTION_PRICE) {
      product.setCostPrice(product.getLastProductionPrice());
      if (product.getAutoUpdateSalePrice()) {
        Beans.get(ProductService.class).updateSalePrice(product);
      }
    }

    manufOrderStockMoveService.finish(manufOrder);
    manufOrder.setRealEndDateT(
        Beans.get(AppProductionService.class).getTodayDateTime().toLocalDateTime());
    manufOrder.setStatusSelect(ManufOrderRepository.STATUS_FINISHED);
    manufOrder.setEndTimeDifference(
        new BigDecimal(
            ChronoUnit.MINUTES.between(
                manufOrder.getPlannedEndDateT(), manufOrder.getRealEndDateT())));
    manufOrderRepo.save(manufOrder);
    AppSupplychain appSupplychain = Beans.get(AppSupplychainService.class).getAppSupplychain();
    if (appSupplychain != null && appSupplychain.getFinishMoAutomaticEmail()) {
      this.sendMail(manufOrder, appSupplychain.getFinishMoMessageTemplate());
    }
  }

  /** Return the cost price for one unit in a manufacturing order. */
  protected BigDecimal computeOneUnitProductionPrice(ManufOrder manufOrder) {
    BigDecimal qty = manufOrder.getQty();
    if (qty.signum() != 0) {
      int scale = Beans.get(AppProductionService.class).getNbDecimalDigitForUnitPrice();
      return manufOrder.getCostPrice().divide(qty, scale, BigDecimal.ROUND_HALF_EVEN);
    } else {
      return BigDecimal.ZERO;
    }
  }

  /**
   * Allows to finish partially a manufacturing order, by realizing current stock move and planning
   * the difference with the planned prodproducts.
   *
   * @param manufOrder
   * @throws AxelorException
   */
  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public void partialFinish(ManufOrder manufOrder) throws AxelorException {
    if (manufOrder.getIsConsProOnOperation()) {
      for (OperationOrder operationOrder : manufOrder.getOperationOrderList()) {
        if (operationOrder.getStatusSelect() == OperationOrderRepository.STATUS_PLANNED) {
          operationOrderWorkflowService.start(operationOrder);
        }
      }
    }
    Beans.get(CostSheetService.class)
        .computeCostPrice(
            manufOrder,
            CostSheetRepository.CALCULATION_PARTIAL_END_OF_PRODUCTION,
            Beans.get(AppBaseService.class).getTodayDate());
    Beans.get(ManufOrderStockMoveService.class).partialFinish(manufOrder);
    AppSupplychain appSupplychain = Beans.get(AppSupplychainService.class).getAppSupplychain();
    if (appSupplychain != null && appSupplychain.getPartFinishMoAutomaticEmail()) {
      this.sendMail(manufOrder, appSupplychain.getPartFinishMoMessageTemplate());
    }
  }

  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public void cancel(ManufOrder manufOrder, CancelReason cancelReason, String cancelReasonStr)
      throws AxelorException {
    if (cancelReason == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(IExceptionMessage.MANUF_ORDER_CANCEL_REASON_ERROR));
    }
    if (manufOrder.getOperationOrderList() != null) {
      for (OperationOrder operationOrder : manufOrder.getOperationOrderList()) {
        if (operationOrder.getStatusSelect() != OperationOrderRepository.STATUS_CANCELED) {
          operationOrderWorkflowService.cancel(operationOrder);
        }
      }
    }

    manufOrderStockMoveService.cancel(manufOrder);

    if (manufOrder.getConsumedStockMoveLineList() != null) {
      manufOrder
          .getConsumedStockMoveLineList()
          .forEach(stockMoveLine -> stockMoveLine.setConsumedManufOrder(null));
    }
    if (manufOrder.getProducedStockMoveLineList() != null) {
      manufOrder
          .getProducedStockMoveLineList()
          .forEach(stockMoveLine -> stockMoveLine.setProducedManufOrder(null));
    }
    if (manufOrder.getDiffConsumeProdProductList() != null) {
      manufOrder.clearDiffConsumeProdProductList();
    }

    manufOrder.setStatusSelect(ManufOrderRepository.STATUS_CANCELED);
    manufOrder.setCancelReason(cancelReason);
    if (Strings.isNullOrEmpty(cancelReasonStr)) {
      manufOrder.setCancelReasonStr(cancelReason.getName());
    } else {
      manufOrder.setCancelReasonStr(cancelReasonStr);
    }
    manufOrderRepo.save(manufOrder);
  }

  public LocalDateTime computePlannedEndDateT(ManufOrder manufOrder) {

    OperationOrder lastOperationOrder = getLastOperationOrder(manufOrder);

    if (lastOperationOrder != null) {

      return lastOperationOrder.getPlannedEndDateT();
    }

    return manufOrder.getPlannedStartDateT();
  }

  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public void allOpFinished(ManufOrder manufOrder) throws AxelorException {
    int count = 0;
    List<OperationOrder> operationOrderList = manufOrder.getOperationOrderList();
    for (OperationOrder operationOrderIt : operationOrderList) {
      if (operationOrderIt.getStatusSelect() == OperationOrderRepository.STATUS_FINISHED) {
        count++;
      }
    }

    if (count == operationOrderList.size()) {
      this.finish(manufOrder);
    }
  }

  /**
   * Returns last operation order (highest priority) of given {@link ManufOrder}
   *
   * @param manufOrder A manufacturing order
   * @return Last operation order of {@code manufOrder}
   */
  public OperationOrder getLastOperationOrder(ManufOrder manufOrder) {
    return operationOrderRepo
        .all()
        .filter("self.manufOrder = ? AND self.plannedEndDateT IS NOT NULL", manufOrder)
        .order("-plannedEndDateT")
        .fetchOne();
  }

  /**
   * Update planned dates.
   *
   * @param manufOrder
   * @param plannedStartDateT
   */
  @Transactional(rollbackOn = {AxelorException.class, RuntimeException.class})
  public void updatePlannedDates(ManufOrder manufOrder, LocalDateTime plannedStartDateT)
      throws AxelorException {
    manufOrder.setPlannedStartDateT(plannedStartDateT);

    if (manufOrder.getOperationOrderList() != null) {
      List<OperationOrder> operationOrderList = getSortedOperationOrderList(manufOrder);
      operationOrderWorkflowService.resetPlannedDates(operationOrderList);

      for (OperationOrder operationOrder : operationOrderList) {
        operationOrderWorkflowService.replan(operationOrder);
      }
    }

    manufOrder.setPlannedEndDateT(computePlannedEndDateT(manufOrder));
  }

  /**
   * Get a list of operation orders sorted by priority and id from the specified manufacturing
   * order.
   *
   * @param manufOrder
   * @return
   */
  private List<OperationOrder> getSortedOperationOrderList(ManufOrder manufOrder) {
    List<OperationOrder> operationOrderList =
        MoreObjects.firstNonNull(manufOrder.getOperationOrderList(), Collections.emptyList());
    Comparator<OperationOrder> byPriority =
        Comparator.comparing(
            OperationOrder::getPriority, Comparator.nullsFirst(Comparator.naturalOrder()));
    Comparator<OperationOrder> byId =
        Comparator.comparing(
            OperationOrder::getId, Comparator.nullsFirst(Comparator.naturalOrder()));

    return operationOrderList
        .stream()
        .sorted(byPriority.thenComparing(byId))
        .collect(Collectors.toList());
  }

  protected void sendMail(ManufOrder manufOrder, Template template) throws AxelorException {
    if (template == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(IExceptionMessage.MANUF_ORDER_MISSING_TEMPLATE));
    }
    try {
      Beans.get(TemplateMessageService.class).generateAndSendMessage(manufOrder, template);
    } catch (Exception e) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR, e.getMessage(), manufOrder);
    }
  }
}
