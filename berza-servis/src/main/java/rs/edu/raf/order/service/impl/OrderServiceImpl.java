package rs.edu.raf.order.service.impl;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Service;
import rs.edu.raf.order.dto.OrderDto;
import rs.edu.raf.order.dto.OrderRequest;
import rs.edu.raf.order.model.Enums.Action;
import rs.edu.raf.order.model.Enums.Type;
import rs.edu.raf.order.model.Order;
import rs.edu.raf.order.repository.OrderRepository;
import rs.edu.raf.order.service.OrderService;
import rs.edu.raf.order.service.mapper.OrderMapper;

import java.math.BigDecimal;
import java.util.*;

@Data
@AllArgsConstructor
@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;

    @Override
    public OrderDto placeOrder(OrderRequest orderRequest) {

        return (orderRequest.getAction().equals(Action.BUY)) ? placeBuyOrder(OrderMapper.mapOrderRequestToOrder(orderRequest)) : placeSellOrder(OrderMapper.mapOrderRequestToOrder(orderRequest));
    }

    private OrderDto placeBuyOrder(Order buyOrder) {
        orderRepository.save(buyOrder);
        checkStopOrderAndStopLimitOrder();

        if (buyOrder.getType().equals(Type.MARKET_ORDER) || buyOrder.getType().equals(Type.LIMIT_ORDER)) {

            // check user balance

            // if available then reserve balance

            List<Order> sellOrders = findAllSellOrdersForTicker(buyOrder.getTicker());
            BigDecimal totalValueChange = BigDecimal.ZERO;
            Map<Order, Integer> matchedSellOrders = new HashMap<>();

            for (Order sellOrder : sellOrders) {
                if (buyOrder.getQuantity() == 0 || (buyOrder.getType().equals(Type.LIMIT_ORDER) && buyOrder.getLimit().compareTo(sellOrder.getLimit()) <= 0)) break;

                if (sellOrder.getQuantity() > buyOrder.getQuantity()) {
                    sellOrder.setQuantity(sellOrder.getQuantity() - buyOrder.getQuantity());
                    totalValueChange = totalValueChange.add(sellOrder.getLimit().multiply(new BigDecimal(buyOrder.getQuantity())));
                    matchedSellOrders.put(sellOrder, buyOrder.getQuantity());
                    buyOrder.setQuantity(0);
                } else {
                    buyOrder.setQuantity(buyOrder.getQuantity() - sellOrder.getQuantity());
                    totalValueChange = totalValueChange.add(sellOrder.getLimit().multiply(new BigDecimal(sellOrder.getQuantity())));
                    matchedSellOrders.put(sellOrder, sellOrder.getQuantity());
                    sellOrder.setQuantity(0);
                }
            }

            if (buyOrder.isAllOrNone() && buyOrder.getQuantity() > 0) {
                if (buyOrder.getStop() == null) return null;
                if (buyOrder.getType().equals(Type.LIMIT_ORDER)) buyOrder.setType(Type.STOP_LIMIT_ORDER);
                else buyOrder.setType(Type.STOP_ORDER);
            }

            // future margin order check

            modifyUserBalance(buyOrder.getUserId(), totalValueChange.negate());
            for (Map.Entry<Order, Integer> entry : matchedSellOrders.entrySet()) {
                Order sellOrder = entry.getKey();
                Integer quantitySold = entry.getValue();
                modifyUserBalance(sellOrder.getUserId(), sellOrder.getLimit().multiply(new BigDecimal(quantitySold)));
            }


            for (Order sellOrder : matchedSellOrders.keySet()) {
                if (sellOrder.getQuantity() == 0) orderRepository.delete(sellOrder);
                else orderRepository.save(sellOrder);
            }
            if (buyOrder.getQuantity() == 0) orderRepository.delete(buyOrder);
            else orderRepository.save(buyOrder);
        }

        return null;
    }

    private OrderDto placeSellOrder(Order sellOrder) {
        orderRepository.save(sellOrder);
        checkStopOrderAndStopLimitOrder();

        if (sellOrder.getType().equals(Type.MARKET_ORDER) || sellOrder.getType().equals(Type.LIMIT_ORDER)) {
            List<Order> buyOrders = findAllBuyOrdersForTicker(sellOrder.getTicker());
            BigDecimal totalValueChange = BigDecimal.ZERO;
            Map<Order, Integer> matchedBuyOrders = new HashMap<>();

            for (Order buyOrder : buyOrders) {
                if (sellOrder.getQuantity() == 0 || (sellOrder.getType().equals(Type.LIMIT_ORDER) && sellOrder.getLimit().compareTo(buyOrder.getLimit()) >= 0)) break;

                if (buyOrder.getQuantity() > sellOrder.getQuantity()) {
                    buyOrder.setQuantity(buyOrder.getQuantity() - sellOrder.getQuantity());
                    totalValueChange = totalValueChange.add(buyOrder.getLimit().multiply(new BigDecimal(sellOrder.getQuantity())));
                    matchedBuyOrders.put(buyOrder, sellOrder.getQuantity());
                    sellOrder.setQuantity(0);
                } else {
                    sellOrder.setQuantity(sellOrder.getQuantity() - buyOrder.getQuantity());
                    totalValueChange = totalValueChange.add(buyOrder.getLimit().multiply(new BigDecimal(buyOrder.getQuantity())));
                    matchedBuyOrders.put(buyOrder, buyOrder.getQuantity());
                    buyOrder.setQuantity(0);
                }
            }

            if (sellOrder.isAllOrNone() && sellOrder.getQuantity() > 0) {
                if (sellOrder.getStop() == null) return null;
                if (sellOrder.getType().equals(Type.LIMIT_ORDER)) sellOrder.setType(Type.STOP_LIMIT_ORDER);
                else sellOrder.setType(Type.STOP_ORDER);
            }

            // future margin order check

            modifyUserBalance(sellOrder.getUserId(), totalValueChange);
            for (Map.Entry<Order, Integer> entry : matchedBuyOrders.entrySet()) {
                Order buyOrder = entry.getKey();
                Integer quantitySold = entry.getValue();
                modifyUserBalance(buyOrder.getUserId(), buyOrder.getLimit().multiply(new BigDecimal(quantitySold)).negate());
            }


            for (Order buyOrder : matchedBuyOrders.keySet()) {
                if (buyOrder.getQuantity() == 0) orderRepository.delete(buyOrder);
                else orderRepository.save(buyOrder);
            }
            if (sellOrder.getQuantity() == 0) orderRepository.delete(sellOrder);
            else orderRepository.save(sellOrder);
        }

        return null;
    }

    @Override
    public BigDecimal approximateOrderValue(OrderRequest orderRequest) {
        Order buyOrder = OrderMapper.mapOrderRequestToOrder(orderRequest);
        List<Order> sellOrders = findAllSellOrdersForTicker(buyOrder.getTicker());
        BigDecimal approximateValue = BigDecimal.ZERO;
        int remainingQuantity = buyOrder.getQuantity();

        switch(buyOrder.getType()) {

            case Type.MARKET_ORDER -> {
                for (Order sellOrder : sellOrders) {
                    if (remainingQuantity == 0) break;

                    int quantityToUse = Math.min(remainingQuantity, sellOrder.getQuantity());
                    approximateValue = approximateValue.add(sellOrder.getLimit().multiply(new BigDecimal(quantityToUse)));
                    remainingQuantity -= quantityToUse;
                }
            }

            case Type.LIMIT_ORDER -> {
                for (Order sellOrder : sellOrders) {
                    if (remainingQuantity == 0 || sellOrder.getLimit().compareTo(buyOrder.getLimit()) >= 0) break;

                    int quantityToUse = Math.min(remainingQuantity, sellOrder.getQuantity());
                    approximateValue = approximateValue.add(sellOrder.getLimit().multiply(new BigDecimal(quantityToUse)));
                    remainingQuantity -= quantityToUse;
                }
                approximateValue = approximateValue.add(buyOrder.getLimit().multiply(new BigDecimal(remainingQuantity)));
            }

            case Type.STOP_ORDER, Type.STOP_LIMIT_ORDER -> approximateValue = approximateValue.add(buyOrder.getStop().multiply(new BigDecimal(remainingQuantity)).multiply(new BigDecimal("1.02")));

        }

        return approximateValue;
    }

    @Override
    public List<OrderDto> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(OrderMapper::toDto)
                .toList();
    }

    @Override
    public List<OrderDto> getOrdersForUser(Long userId) {
        return orderRepository.findAllByUserId(userId);
    }

    private void checkStopOrderAndStopLimitOrder() {
        List<Order> allOrders = orderRepository.findAll();
        for (Order order : allOrders) {
            if (order.getType().equals(Type.STOP_ORDER)) {
                if (order.getAction().equals(Action.BUY)) {
                    List<Order> sellOrders = findAllSellOrdersForTicker(order.getTicker());
                    if (!sellOrders.isEmpty() && sellOrders.get(0).getLimit().compareTo(order.getStop()) >= 0) {
                        order.setType(Type.MARKET_ORDER);
                        placeBuyOrder(order);
                    }
                } else if (order.getAction().equals(Action.SELL)) {
                    List<Order> buyOrders = findAllBuyOrdersForTicker(order.getTicker());
                    if (!buyOrders.isEmpty() && buyOrders.get(0).getLimit().compareTo(order.getStop()) <= 0) {
                        order.setType(Type.MARKET_ORDER);
                        placeSellOrder(order);
                    }
                }
            } else if (order.getType().equals(Type.STOP_LIMIT_ORDER)) {
                if (order.getAction().equals(Action.BUY)) {
                    List<Order> sellOrders = findAllSellOrdersForTicker(order.getTicker());
                    if (!sellOrders.isEmpty() && sellOrders.get(0).getLimit().compareTo(order.getStop()) >= 0) {
                        order.setType(Type.LIMIT_ORDER);
                        placeBuyOrder(order);
                    }
                } else if (order.getAction().equals(Action.SELL)) {
                    List<Order> buyOrders = findAllBuyOrdersForTicker(order.getTicker());
                    if (!buyOrders.isEmpty() && buyOrders.get(0).getLimit().compareTo(order.getStop()) <= 0) {
                        order.setType(Type.LIMIT_ORDER);
                        placeSellOrder(order);
                    }
                }
            }
        }
    }

    @Override
    public List<Order> findAllBuyOrdersForTicker(String ticker) {
        return orderRepository.findAllByActionAndTicker(Action.BUY, ticker)
                .stream()
                .sorted(Comparator.comparing(Order::getLimit).reversed())
                .toList();
    }

    @Override
    public List<Order> findAllSellOrdersForTicker(String ticker) {
        return orderRepository.findAllByActionAndTicker(Action.SELL, ticker)
                .stream()
                .sorted(Comparator.comparing(Order::getLimit))
                .toList();
    }

    private String nadjiVrstuRacuna(Long BrojRacuna) {
        if (BrojRacuna % 100 == 11) return "DevizniRacun";
        if (BrojRacuna % 100 == 22) return "PravniRacun";
        if (BrojRacuna % 100 == 33) return "TekuciRacun";
        return null;
    }

    private void modifyUserBalance(Long userId, BigDecimal valueChange) {
//        String userServiceUrl = "http://localhost:8080/user-service/korisnik/id/" + userId;
//        RestTemplate restTemplate = new RestTemplate();
//        ResponseEntity<KorisnikDTO> response = restTemplate.getForEntity(userServiceUrl, KorisnikDTO.class);
//        KorisnikDTO korisnikDTO = response.getBody();
//
//        if (korisnikDTO == null) return;
//
//        String[] racuni = korisnikDTO.getPovezaniRacuni().split(",");
//        List<String> devizniRacuni = new ArrayList<>();
//        List<String> pravniRacuni = new ArrayList<>();
//        List<String> tekuciRacuni = new ArrayList<>();
//
//        for (String racun : racuni) {
//            String vrstaRacuna = nadjiVrstuRacuna(Long.parseLong(racun));
//            if (vrstaRacuna == null) continue;
//            else if (vrstaRacuna.equals("DevizniRacun")) devizniRacuni.add(racun);
//            else if (vrstaRacuna.equals("PravniRacun")) pravniRacuni.add(racun);
//            else if (vrstaRacuna.equals("TekuciRacun")) tekuciRacuni.add(racun);
//        }


    }

}
