package gg.peastudios.peashops.shop;

// what kind of trade a shop offers.
public enum ShopType {
    BUY,    // players buy from the shop (shop has stock; player gives money)
    SELL,   // shop buys from players (shop pays; player gives stock)
    TRADE,  // bidirectional — buy and sell, separate prices
    ADMIN   // infinite stock + infinite money; admin only
}
