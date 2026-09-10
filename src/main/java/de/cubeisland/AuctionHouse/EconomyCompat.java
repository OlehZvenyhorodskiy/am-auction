package de.cubeisland.AuctionHouse;

import de.cubeisland.AuctionHouse.Auction.Bidder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Vault compatibility layer.
 *
 * Some AquaMix nodes expose an Economy interface where the deprecated
 * String overloads are not present at runtime. Calling econ.withdrawPlayer(String, double)
 * directly can therefore throw NoSuchMethodError.  All economy calls used by the
 * GUI buy path go through reflection here and try OfflinePlayer, Player and name
 * signatures in a safe order.
 */
public final class EconomyCompat
{
    private EconomyCompat()
    {
    }

    public static final class Result
    {
        private final boolean invoked;
        private final boolean success;
        private final String error;
        private final String signature;

        private Result(boolean invoked, boolean success, String error, String signature)
        {
            this.invoked = invoked;
            this.success = success;
            this.error = error == null ? "" : error;
            this.signature = signature == null ? "" : signature;
        }

        public static Result success(String signature)
        {
            return new Result(true, true, "", signature);
        }

        public static Result failure(String error, String signature)
        {
            return new Result(true, false, error, signature);
        }

        public static Result unavailable(String error)
        {
            return new Result(false, false, error, "");
        }

        public boolean wasInvoked()
        {
            return this.invoked;
        }

        public boolean isSuccess()
        {
            return this.success;
        }

        public String getError()
        {
            return this.error;
        }

        public String getSignature()
        {
            return this.signature;
        }
    }

    public static double getBalance(Economy economy, Bidder bidder)
    {
        if (economy == null || bidder == null)
        {
            return 0.0D;
        }
        Object[] candidates = candidates(bidder);
        StringBuilder errors = new StringBuilder();
        for (Object candidate : candidates)
        {
            if (candidate == null)
            {
                continue;
            }
            Invocation invocation = invoke(economy, "getBalance", candidate, Double.NaN);
            if (invocation.invoked)
            {
                Object value = invocation.value;
                if (value instanceof Number)
                {
                    return ((Number)value).doubleValue();
                }
                errors.append(invocation.signature).append(" returned ").append(value).append("; ");
            }
            else if (!invocation.error.isEmpty())
            {
                errors.append(invocation.error).append("; ");
            }
        }
        AuctionHouse.log("[BuyFix152] Vault getBalance failed for bidder=" + bidder.getName() + " provider=" + economy.getName() + " errors=" + errors);
        return 0.0D;
    }

    public static Result withdraw(Economy economy, Bidder bidder, double amount)
    {
        return transaction(economy, bidder, amount, "withdrawPlayer");
    }

    public static Result deposit(Economy economy, Bidder bidder, double amount)
    {
        return transaction(economy, bidder, amount, "depositPlayer");
    }

    private static Result transaction(Economy economy, Bidder bidder, double amount, String methodName)
    {
        if (economy == null || bidder == null)
        {
            return Result.unavailable("economy or bidder is null");
        }
        Object[] candidates = candidates(bidder);
        StringBuilder errors = new StringBuilder();
        for (Object candidate : candidates)
        {
            if (candidate == null)
            {
                continue;
            }
            Invocation invocation = invoke(economy, methodName, candidate, amount);
            if (!invocation.invoked)
            {
                if (!invocation.error.isEmpty())
                {
                    errors.append(invocation.error).append("; ");
                }
                continue;
            }
            Object response = invocation.value;
            if (isTransactionSuccess(response))
            {
                return Result.success(invocation.signature);
            }
            return Result.failure(errorMessage(response), invocation.signature);
        }
        return Result.unavailable("no compatible Vault method for " + methodName + " provider=" + economy.getClass().getName() + " errors=" + errors);
    }

    private static Object[] candidates(Bidder bidder)
    {
        OfflinePlayer off = bidder.getOffPlayer();
        Player player = bidder.getPlayer();
        String name = bidder.getName();
        return new Object[] { off, player, name };
    }

    private static Invocation invoke(Economy economy, String methodName, Object firstArg, double amount)
    {
        Method best = findMethod(economy.getClass(), methodName, firstArg, !Double.isNaN(amount));
        if (best == null)
        {
            return Invocation.notInvoked("missing " + methodName + "(" + firstArg.getClass().getName() + (Double.isNaN(amount) ? "" : ",double") + ")");
        }
        try
        {
            if (!best.canAccess(economy))
            {
                best.setAccessible(true);
            }
            Object value = Double.isNaN(amount) ? best.invoke(economy, firstArg) : best.invoke(economy, firstArg, amount);
            return Invocation.invoked(value, best.getName() + "(" + best.getParameterTypes()[0].getSimpleName() + (Double.isNaN(amount) ? "" : ",double") + ")");
        }
        catch (Throwable ex)
        {
            Throwable root = rootCause(ex);
            return Invocation.notInvoked(best.getName() + " failed: " + root.getClass().getSimpleName() + ": " + root.getMessage());
        }
    }

    private static Method findMethod(Class<?> type, String methodName, Object firstArg, boolean twoArgs)
    {
        Method fallback = null;
        for (Method method : type.getMethods())
        {
            if (!method.getName().equals(methodName))
            {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length != (twoArgs ? 2 : 1))
            {
                continue;
            }
            if (twoArgs && !(params[1] == double.class || params[1] == Double.class))
            {
                continue;
            }
            if (!isCompatible(params[0], firstArg))
            {
                continue;
            }
            if (params[0] == String.class)
            {
                fallback = method;
                continue;
            }
            return method;
        }
        return fallback;
    }

    private static boolean isCompatible(Class<?> parameter, Object value)
    {
        if (value == null)
        {
            return false;
        }
        if (parameter.isPrimitive())
        {
            return false;
        }
        if (parameter.isInstance(value))
        {
            return true;
        }
        return parameter == String.class && value instanceof String;
    }

    private static boolean isTransactionSuccess(Object response)
    {
        if (response == null)
        {
            return true;
        }
        try
        {
            Method method = response.getClass().getMethod("transactionSuccess");
            Object result = method.invoke(response);
            return result instanceof Boolean && ((Boolean)result).booleanValue();
        }
        catch (Throwable ignored)
        {
            return true;
        }
    }

    private static String errorMessage(Object response)
    {
        if (response == null)
        {
            return "unknown economy error";
        }
        try
        {
            Field field = response.getClass().getField("errorMessage");
            Object value = field.get(response);
            return String.valueOf(value);
        }
        catch (Throwable ignored)
        {
            return String.valueOf(response);
        }
    }

    private static Throwable rootCause(Throwable throwable)
    {
        Throwable result = throwable;
        while (result.getCause() != null && result.getCause() != result)
        {
            result = result.getCause();
        }
        return result;
    }

    private static final class Invocation
    {
        private final boolean invoked;
        private final Object value;
        private final String error;
        private final String signature;

        private Invocation(boolean invoked, Object value, String error, String signature)
        {
            this.invoked = invoked;
            this.value = value;
            this.error = error == null ? "" : error;
            this.signature = signature == null ? "" : signature;
        }

        static Invocation invoked(Object value, String signature)
        {
            return new Invocation(true, value, "", signature);
        }

        static Invocation notInvoked(String error)
        {
            return new Invocation(false, null, error, "");
        }
    }
}
